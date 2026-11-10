package com.shilapi.xcertplay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.location.AndroidCarPlayLocationProvider
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.WirelessStartupFailure
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.transport.VehicleSpeedLocationProvider
import java.io.File
import java.util.concurrent.Executors

/** Owns connection, decoding and recovery; the full-screen Activity supplies only a visible surface. */
class DiPlaySessionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val teardown = Executors.newSingleThreadExecutor { Thread(it, "carplay-session-teardown") }
    private val retryBudget = WirelessStartupRetryBudget()
    private var generation = 0
    private var width = 0
    private var height = 0
    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var output: FirstFrameOutput? = null
    private var activeSession: AirPlaySession? = null
    private var sessionWireless = true
    @Volatile private var uiListener: AirPlaySessionListener? = null
    @Volatile private var uiStatus: ((CarPlayStatus) -> Unit)? = null
    private var lastStatus: CarPlayStatus? = null
    private var stopping = false
    private var reconnectScheduled = false
    private var reconnectAttempts = 0
    private var retryStopped = false
    private var tearingDown = false
    private var startAfterTeardown = false

    override fun onCreate() { super.onCreate(); instance = this }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else {
            // Android 7.x has no notification channels; the priority stands in for the channel importance.
            @Suppress("DEPRECATION")
            Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, DiPlayActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText("CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (Build.VERSION.SDK_INT >= 30 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            // Without it Android stops location updates while another car app (the reversing camera,
            // the car's own map) covers CarPlay, and the iPhone gets no position until DiPlay is back.
            if (AirPlayPersistence.loadLocationReportingEnabled(this) &&
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(1, notification, types)
        } else startForeground(1, notification)
        when (intent?.action) {
            ACTION_START -> if (controller == null) {
                width = intent.getIntExtra(EXTRA_WIDTH, 0)
                height = intent.getIntExtra(EXTRA_HEIGHT, 0)
                if (width > 0 && height > 0) {
                    retryBudget.manualRetry()
                    retryStopped = false
                    if (tearingDown) startAfterTeardown = true
                    else {
                        stopping = false
                        startSession()
                    }
                }
            }
            ACTION_RECONNECT -> if (!stopping && !tearingDown && width > 0 && height > 0) {
                width = intent.getIntExtra(EXTRA_WIDTH, width).takeIf { it > 0 } ?: width
                height = intent.getIntExtra(EXTRA_HEIGHT, height).takeIf { it > 0 } ?: height
                retryBudget.manualRetry()
                retryStopped = false
                restartSession("settings changed")
            }
        }
        return START_NOT_STICKY
    }

    private fun startSession() {
        if (stopping || tearingDown || controller != null) return
        val current = ++generation
        CarPlayLiveSession.preparing()
        try {
            DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this))
            sessionWireless = AirPlayPersistence.loadWirelessEnabled(this)
            val configuration = CarPlaySessionConfig.create(this, width, height)
            val airPlay = configuration.airPlay
            val offscreen = FirstFrameOutput(airPlay.main.widthPixels, airPlay.main.heightPixels)
            output = offscreen
            val renderer = AndroidMediaSink(
                surface = offscreen.surface,
                videoWidth = airPlay.main.widthPixels,
                videoHeight = airPlay.main.heightPixels,
                preferSoftwareHevcDecoder = Build.VERSION.SDK_INT >= 29 && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this),
                advancedAudioChannelMapping = resources.getBoolean(R.bool.config_advanced_audio_channel_mapping) &&
                    AirPlayPersistence.loadAdvancedAudioChannelMapping(this),
                audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
                audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(this),
                mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
                navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
                context = this,
                navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this),
                mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
                onAudioDiagnostic = { Log.d(TAG, it) },
                onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
                callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(this),
                callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(this),
                videoPacingDelayMillis = if (AirPlayPersistence.loadSmoothVideo(this)) smoothVideoDelayMillis(airPlay.main.fps) else 0,
                mainVideoFrameRate = airPlay.main.fps,
            )
            output = offscreen
            sink = renderer
            val media = CarPlayMediaEngine(renderer, airPlay.microphone,
                File(filesDir, "audio-capture.enabled").takeIf { it.isFile }?.let { File(filesDir, "audio-captures") })
            val runtime = configuration.runtime
            val locationProvider = if (!runtime.locationReportingEnabled) null else if (runtime.identification.vehicleSpeedEnabled) {
                VehicleSpeedLocationProvider(
                    AndroidCarPlayLocationProvider(this),
                    if (Lynk09Settings.instrumentToIphoneActive(this)) Lynk09InstrumentSource(applicationContext)
                    else com.shilapi.xcertplay.hud.BydNavigationOutputs.wheelSpeed(applicationContext),
                )
            } else AndroidCarPlayLocationProvider(this)
            val pairings = AirPlayPersistence.loadPairings(this) { id, key -> AirPlayPersistence.savePairing(this, id, key) }
            val next = CarPlayController(
                context = this, config = runtime, airPlayConfig = airPlay,
                identity = AirPlayPersistence.loadIdentity(this), pairings = pairings,
                listener = sessionListener(current), media = media,
                reportStatus = { status -> onStatus(current, status) },
                loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
                savePairRecord = { AirPlayPersistence.saveLockdownRecord(this, it) },
                clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
                locationProvider = locationProvider,
                vehicleStatusProvider = if (!Lynk09Settings.instrumentToIphone(this) &&
                    com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(this))
                    com.shilapi.xcertplay.hud.BydNavigationOutputs.batteryStatus(applicationContext) else null,
            )
            controller = next
            CarPlayMediaKeys.attach(this, next)
            if (airPlay.videoInCar) CarPlayVideo.attach(this, next)
            CarPlayBackgroundSession.store(next, renderer, width, height, this, configuration.display) { completion ->
                main.post { stopSession(completion) }
            }
            next.start()
        } catch (error: Throwable) {
            Log.e(TAG, "CarPlay session could not start", error)
            CarPlayLiveSession.reconnecting("Connection failed: ${error.message ?: error.javaClass.simpleName}")
            scheduleReconnect("startup failed", WirelessStartupFailure.FIRST_TCP_TIMEOUT)
        }
    }

    private fun sessionListener(current: Int): AirPlaySessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            main.post {
                if (current != generation || stopping) return@post
                activeSession = session
                CarPlayBackgroundSession.active = true
                reconnectAttempts = 0
                CarPlayLiveSession.connected()
                Log.i(TAG, "AirPlay session active generation=$current")
                uiListener?.onSessionActive(session)
            }
        }
        override fun onVideoFrameRendered(session: AirPlaySession) {
            main.post {
                if (current != generation || stopping || activeSession !== session) return@post
                CarPlayLiveSession.streaming(getString(R.string.carplay_connected))
                DiPlayPreferences.saveLastUsedWireless(this@DiPlaySessionService, sessionWireless)
                Log.i(TAG, "first decoded frame rendered generation=$current")
                if (retryBudget.firstFrame(session, SystemClock.elapsedRealtime())) {
                    main.postDelayed({
                        if (current == generation && activeSession === session &&
                            retryBudget.resetIfStable(session, SystemClock.elapsedRealtime())) {
                            Log.i(TAG, "CarPlay first frame remained stable; retry budget reset")
                        }
                    }, com.shilapi.xcertplay.network.WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
                uiListener?.onVideoFrameRendered(session)
            }
        }
        override fun onSessionEnded(session: AirPlaySession) {
            main.post {
                if (current != generation || stopping || activeSession != null && activeSession !== session) return@post
                activeSession = null
                CarPlayBackgroundSession.active = false
                retryBudget.disconnected()
                CarPlayLiveSession.reconnecting("CarPlay disconnected; reconnecting")
                Log.i(TAG, "AirPlay session ended generation=$current")
                uiListener?.onSessionEnded(session)
                scheduleReconnect("AirPlay session ended")
            }
        }
        override fun onTransportError(message: String) {
            main.post {
                if (current != generation || stopping) return@post
                CarPlayLiveSession.reconnecting("Connection interrupted; reconnecting")
                uiListener?.onTransportError(message)
                scheduleReconnect(message)
            }
        }
        override fun onDebugLog(message: String) { uiListener?.onDebugLog(message) }
        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            uiListener?.onCommand(session, type, params)
        }
        override fun onHostUiRequested(session: AirPlaySession) { uiListener?.onHostUiRequested(session) }
        override fun onVideoPlaybackUiRequested(session: AirPlaySession) { uiListener?.onVideoPlaybackUiRequested(session) }
        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            uiListener?.onRemoteControlMessage(session, streamId, message)
        }
    }

    private fun onStatus(current: Int, status: CarPlayStatus) {
        main.post {
            if (current != generation || stopping) return@post
            lastStatus = status
            Log.i(TAG, "status=${status.javaClass.simpleName} generation=$current")
            uiStatus?.invoke(status)
            if (status is CarPlayStatus.Failed) {
                val message = when {
                    status.wifiResetRequired -> getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
                    status.startupFailure == WirelessStartupFailure.HOTSPOT_NOT_READY -> getString(R.string.hotspot_network_not_ready)
                    status.startupFailure == WirelessStartupFailure.FIRST_TCP_TIMEOUT -> getString(R.string.first_tcp_timeout)
                    status.message.contains("createGroup failed", true) -> getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
                    status.message.contains("permission", true) -> getString(R.string.allow_the_connection_permission_to_continue)
                    else -> getString(R.string.connection_interrupted_retrying)
                }
                CarPlayLiveSession.reconnecting(message, status.wifiResetRequired)
                if (status.wifiResetRequired) retryStopped = true
                else scheduleReconnect(status.message, status.startupFailure)
            } else if (CarPlayLiveSession.value().phase != CarPlayLiveSession.Phase.Streaming) {
                CarPlayLiveSession.connecting(describeStatus(status))
            }
        }
    }

    private fun describeStatus(status: CarPlayStatus): String = when (status) {
        CarPlayStatus.DiscoveringMfi -> getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> getString(R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady -> getString(R.string.status_hotspot_ready,
            status.backend, status.ssid, status.band,
            if (status.channel == 0) getString(R.string.auto_value) else status.channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork -> getString(R.string.starting_airplay_service)
        CarPlayStatus.RunningControl -> getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> getString(R.string.status_failed, status.message)
    }

    private fun scheduleReconnect(reason: String, startupFailure: WirelessStartupFailure? = null) {
        if (stopping || reconnectScheduled || retryStopped) return
        val startupDelay = if (startupFailure != null && startupFailure != WirelessStartupFailure.HOTSPOT_CONFIGURATION)
            retryBudget.nextDelayMillis() else null
        if (startupFailure != null && startupDelay == null) {
            retryStopped = true
            CarPlayLiveSession.connecting("$reason; reconnect manually")
            return
        }
        reconnectScheduled = true
        val current = generation
        val delay = startupDelay ?: (2_000L * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        reconnectAttempts++
        main.postDelayed({
            if (current == generation && !stopping) {
                reconnectScheduled = false
                restartSession(reason)
            }
        }, delay)
    }

    private fun restartSession(reason: String) {
        if (stopping) return
        Log.i(TAG, "Restarting CarPlay: $reason")
        val old = controller
        val oldSink = sink
        val oldOutput = output
        ++generation
        tearingDown = true
        controller = null
        sink = null
        output = null
        activeSession = null
        lastStatus = null
        reconnectScheduled = false
        CarPlayBackgroundSession.active = false
        CarPlayLiveSession.preparing("Reconnecting CarPlay")
        CarPlayMediaKeys.detach(old)
        CarPlayBackgroundSession.clear(old, keepOwner = true)
        teardown.execute {
            old?.close()
            old?.awaitClosed(4_000L)
            oldSink?.close()
            oldSink?.awaitVideoReleased(2_000L)
            oldOutput?.close()
            main.post {
                tearingDown = false
                if (!stopping && controller == null) {
                    startAfterTeardown = false
                    startSession()
                }
            }
        }
    }

    private fun stopSession(completion: () -> Unit = {}) {
        if (stopping) { completion(); return }
        stopping = true
        tearingDown = true
        ++generation
        val old = controller
        val oldSink = sink
        val oldOutput = output
        controller = null
        sink = null
        output = null
        activeSession = null
        uiListener = null
        uiStatus = null
        CarPlayMediaKeys.detach(old)
        CarPlayBackgroundSession.clear(old)
        CarPlayLiveSession.idle()
        teardown.execute {
            old?.close()
            old?.awaitClosed(4_000L)
            oldSink?.close()
            oldSink?.awaitVideoReleased(2_000L)
            oldOutput?.close()
            main.post {
                tearingDown = false
                completion()
                if (startAfterTeardown) {
                    startAfterTeardown = false
                    stopping = false
                    startSession()
                } else {
                    // A transport switch stops the old session and starts this same Service
                    // from its completion callback. Let that ACTION_START reach onStartCommand
                    // before retiring the Service instance.
                    main.postDelayed({ if (stopping && controller == null && !tearingDown) stopSelf() }, 2_000L)
                }
            }
        }
    }

    private fun attach(listener: AirPlaySessionListener, status: (CarPlayStatus) -> Unit) {
        uiListener = listener
        uiStatus = status
        activeSession?.let(listener::onSessionActive)
        lastStatus?.let(status)
    }

    private fun detach(listener: AirPlaySessionListener) {
        if (uiListener === listener) {
            uiListener = null
            uiStatus = null
            output?.surface?.let { sink?.setSurface(110, it) }
        }
    }

    private class FirstFrameOutput(width: Int, height: Int) {
        private val thread = HandlerThread("carplay-initial-frame").apply { start() }
        private val reader = ImageReader.newInstance(width.coerceAtLeast(1), height.coerceAtLeast(1), ImageFormat.PRIVATE, 3)
        val surface = reader.surface
        init { reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.close() }, Handler(thread.looper)) }
        fun close() { reader.close(); thread.quitSafely() }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // BYD's recents force-stops the package ~10 ms after removing the task: end guidance first.
        com.shilapi.xcertplay.hud.BydNavigationOutputs.endNow()
        stopSession()
    }
    override fun onDestroy() {
        if (!stopping) stopSession()
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object {
        private const val TAG = "DiPlaySession"
        const val ACTION_START = "com.shihab.diplay.START"
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
        const val ACTION_RECONNECT = "com.shihab.diplay.RECONNECT"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        private const val CHANNEL = "diplay_connection"
        @Volatile private var instance: DiPlaySessionService? = null

        fun start(context: Context, width: Int, height: Int) {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, DiPlaySessionService::class.java).setAction(ACTION_START)
                    .putExtra(EXTRA_WIDTH, width).putExtra(EXTRA_HEIGHT, height))
        }
        fun reconnect(context: Context, width: Int = 0, height: Int = 0) {
            androidx.core.content.ContextCompat.startForegroundService(context,
                Intent(context, DiPlaySessionService::class.java).setAction(ACTION_RECONNECT)
                    .putExtra(EXTRA_WIDTH, width).putExtra(EXTRA_HEIGHT, height))
        }
        fun stop(context: Context) {
            context.startService(Intent(context, DiPlaySessionService::class.java).setAction(ACTION_STOP))
        }
        fun attachUi(listener: AirPlaySessionListener, status: (CarPlayStatus) -> Unit) { instance?.attach(listener, status) }
        fun detachUi(listener: AirPlaySessionListener) { instance?.detach(listener) }
        fun parkVideo() { instance?.let { it.sink?.setSurface(110, it.output?.surface ?: return) } }
    }
}
