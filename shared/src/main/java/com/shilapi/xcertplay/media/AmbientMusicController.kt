package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.hud.BydAmbientLightClient
import com.shilapi.xcertplay.hud.BydAmbientLightPolicy
import com.shilapi.xcertplay.hud.BydCarMusicRhythm
import com.shilapi.xcertplay.hud.EcarxAmbientLightClient
import com.shilapi.xcertplay.hud.EcarxAmbientLightPolicy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/** The sole scheduler owns lamp I/O; audio writes only append small envelope metadata. */
object AmbientMusicController {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "carplay-ambient-music").apply { isDaemon = true }
    }
    private val sequence = AtomicLong()
    private val activeSink = AtomicLong()
    private val activeRenderer = AtomicLong()
    private val phonePlayback = AmbientPhonePlaybackState()
    @Volatile private var artworkOwner: Any? = null
    private var albumColor: Int? = null
    @Volatile private var settings = AmbientMusicSettings.Values()
    private var context: Context? = null
    private var envelope: AmbientMusicEnvelope? = null
    private var playback: (() -> Pair<Int, Boolean>)? = null
    private var allowed = false
    private var colors = AmbientMusicColorDetector()
    private var brightnessEnvelope = AmbientMusicBrightness(settings.speed)
    private var appliedCount = 0L
    private var lastLogMillis = 0L
    private var idleSinceMillis: Long? = null

    // ECARX/Geely path: the same analysis drives a 24-bit RGB lamp surface instead of BYD's
    // 31 numbered colours. Kept beside the BYD session so neither backend changes the other.
    @Volatile private var ecarx: EcarxAmbientLightClient? = null
    private var ecarxOriginal: EcarxAmbientLightClient.Snapshot? = null
    private var ecarxActive = false
    private var ecarxLast: Triple<Int, Int, Int>? = null
    private var ecarxHue: Double? = null
    private var ecarxHueAt = 0L
    private var ecarxRetryAt = 0L
    private var ecarxFailures = 0
    private val ecarxCadence = AmbientMusicApplyCadence(50L)
    private var beatModel = AmbientBeatModel()

    private val lampSession = AmbientLampSession(
        factory = {
            val real = BydAmbientLightClient(requireNotNull(context))
            object : AmbientLampSession.Client {
                override fun unhealthy(): Boolean {
                    val phase = real.status().phase
                    return phase == BydAmbientLightClient.Phase.FAILED || phase == BydAmbientLightClient.Phase.CLOSED
                }
                override fun begin(seed: BydAmbientLightPolicy.Snapshot?) = real.begin(seed?.let {
                    BydAmbientLightClient.State(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area)
                }).thenApply { BydAmbientLightPolicy.Snapshot(it.frontColor, it.backColor, it.frontBrightness, it.backBrightness, it.area) }
                override fun apply(desired: Triple<Int, Int, Int>) = real.apply(desired.first, desired.second, desired.third).thenApply { Unit }
                override fun stop() = real.stop().thenApply { Unit }
                // stop's future completes only after closeTransport, including its failure path.
                override fun close() = real.close()
            }
        },
        dispatch = { executor.execute(it) },
        now = { android.os.SystemClock.uptimeMillis() },
        log = { Log.i("DiPlay-Ambient", it) },
        applied = { desired ->
            appliedCount++
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastLogMillis >= 5_000) {
                lastLogMillis = now
                Log.i("DiPlay-Ambient", "applied=$appliedCount area=${desired.first} color=${desired.second} brightness=${desired.third} colorMode=${settings.colorMode} estimatedBpm=${colors.estimatedBpm?.toInt() ?: 0}")
            }
        },
    )

    // The 20 Hz lamp update, scheduled only while the feature (or the car's own rhythm) is on; turning it off
    // restores the lamps by itself.
    private var ticking: ScheduledFuture<*>? = null
    private const val BAND_INTERVAL_MILLIS = 90L
    private const val SPECTRAL_FALLBACK_MILLIS = 700L
    private const val NIGHT_START_HOUR = 19
    private const val DAY_START_HOUR = 7

    /** On the executor, after [settings] changed. */
    private fun updateTicking() {
        // The car's own rhythm is a BYD-only feature; never start it from an ECARX head unit.
        val ownRhythm = settings.carRhythm && ecarx == null
        if (!ownRhythm) context?.let { BydCarMusicRhythm.setPlaying(it, false) }
        if (!settings.enabled && !ownRhythm) {
            ticking?.cancel(false)
            ticking = null
        } else if (ticking == null) {
            ticking = executor.scheduleWithFixedDelay({ runCatching { tick() }.onFailure {
                Log.w("DiPlay-Ambient", "lamp update failed", it)
                lampSession.recover()
            } }, 50, 50, TimeUnit.MILLISECONDS)
        }
    }

    @Synchronized fun openSink(context: Context?): Long {
        if (context == null) return 0L
        val token = sequence.incrementAndGet()
        activeSink.set(token)
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != token) return@execute
            this.context = context.applicationContext
            settings = AmbientMusicSettings.load(context)
            resetColors()
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            beatModel = AmbientBeatModel()
            val client = EcarxAmbientLightClient(context.applicationContext)
            ecarx = if (client.present()) client.logPresence() else null
            ecarxOriginal = null
            ecarxActive = false
            ecarxLast = null
            ecarxRetryAt = 0L
            ecarxFailures = 0
            ecarxCadence.reset()
            allowed = settings.enabled; envelope = null; playback = null
            idleSinceMillis = longIdle()
            if (!settings.enabled) restoreOriginal()
            updateTicking()
        }
        return token
    }

    private fun EcarxAmbientLightClient.logPresence(): EcarxAmbientLightClient {
        Log.i("DiPlay-Ambient", "ECARX ambient light available supported=${supported()}")
        return this
    }

    @Synchronized internal fun openRenderer(sink: Long, envelope: AmbientMusicEnvelope,
        playback: () -> Pair<Int, Boolean>): Long {
        if (sink == 0L || sink != activeSink.get()) return 0L
        val token = sequence.incrementAndGet()
        activeRenderer.set(token)
        executor.execute {
            if (activeSink.get() != sink || activeRenderer.get() != token) return@execute
            allowed = true
            this.envelope = envelope
            this.playback = playback
        }
        return token
    }

    private fun feeding() = settings.carRhythm && settings.carRhythmFeed

    internal fun wantsPcm(token: Long): Boolean = token != 0L && token == activeRenderer.get() &&
        ((settings.enabled && settings.music) || feeding())

    /** Whether the played PCM should also be reduced to the 16 rhythm bands for the car. */
    internal fun wantsBands(token: Long): Boolean = token != 0L && token == activeRenderer.get() &&
        (feeding() || (ecarx != null && settings.enabled && settings.music))

    /** Binds phone playback callbacks to the current CarPlay media owner. */
    @Synchronized fun claimPlaybackOwner(owner: Any) {
        phonePlayback.claim(owner)
        if (artworkOwner === owner) return
        artworkOwner = owner
        albumColorChanged(owner, null)
    }

    /** Already decoded artwork is sampled on the existing artwork worker, never on PCM writes. */
    fun albumColorChanged(owner: Any, color: Int?) {
        executor.execute {
            if (artworkOwner !== owner) return@execute
            val valid = color?.takeIf { it in 1..31 }
            if (albumColor == valid) return@execute
            albumColor = valid
            if (settings.colorSource == AmbientColorSource.ALBUM) resetColors(preserveTiming = true)
        }
    }

    /** Stale callbacks from a replaced media owner are ignored. */
    fun phonePlaybackChanged(owner: Any, playing: Boolean): Boolean = phonePlayback.update(owner, playing)

    @Synchronized fun releasePlaybackOwner(owner: Any): Boolean {
        if (artworkOwner === owner) {
            artworkOwner = null
            executor.execute {
                if (artworkOwner != null) return@execute
                albumColor = null
                if (settings.colorSource == AmbientColorSource.ALBUM) resetColors(preserveTiming = true)
            }
        }
        return phonePlayback.release(owner)
    }

    private fun resetColors(preserveTiming: Boolean = false) {
        val palette = ambientColorPalette(settings.colorSource, settings.selectedColors, albumColor)
        val initial = if (settings.colorSource == AmbientColorSource.ALBUM) albumColor ?: palette.first()
            else settings.color
        val random = if (settings.colorSource == AmbientColorSource.ALBUM && albumColor == null)
            ({ size: Int -> kotlin.random.Random.nextInt(size) }) else null
        if (preserveTiming) colors.updatePalette(initial, palette, random)
        else colors = AmbientMusicColorDetector(initial, palette, AmbientColorSpeed.STANDARD, random)
    }

    /** Checks existing authorization and lamp readback only; never asks for ADB or writes a lamp. */
    fun checkSupport(context: Context): java.util.concurrent.CompletableFuture<Boolean> {
        val result = java.util.concurrent.CompletableFuture<Boolean>()
        executor.execute {
            if (lampSession.isReady()) {
                result.complete(true)
                return@execute
            }
            val ecarxLamps = EcarxAmbientLightClient(context.applicationContext)
            if (ecarxLamps.present() && ecarxLamps.supported()) {
                result.complete(true)
                return@execute
            }
            val client = BydAmbientLightClient(context.applicationContext)
            client.read().handle { _, error -> error == null }.thenCompose { supported ->
                // Release the read-only worker before an enabled session can acquire its process lock.
                client.stop().handle { _, error ->
                    client.close()
                    supported && error == null
                }
            }.whenComplete { supported, error -> result.complete(error == null && supported == true) }
        }
        return result
    }

    fun settingsChanged(context: Context) {
        executor.execute {
            this.context = context.applicationContext
            settings = AmbientMusicSettings.load(context)
            resetColors()
            brightnessEnvelope = AmbientMusicBrightness(settings.speed)
            beatModel = AmbientBeatModel()
            ecarxLast = null
            ecarxCadence.reset()
            allowed = settings.enabled
            if (!settings.enabled) { restoreOriginal(); restoreEcarx(clearOriginal = true) }
            updateTicking()
        }
    }

    /** Silence that began before this session: the car's own lighting returns without a first lamp session. */
    private fun longIdle() = android.os.SystemClock.uptimeMillis() - AmbientMusicActivityPolicy.RESTORE_AFTER_MILLIS

    @Synchronized internal fun closeRenderer(token: Long) {
        if (token == 0L || !activeRenderer.compareAndSet(token, 0L)) return
        executor.execute {
            if (activeRenderer.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            if (!settings.enabled) { restoreOriginal(); restoreEcarx(clearOriginal = true) }
        }
    }

    @Synchronized fun closeSink(token: Long) {
        if (token == 0L || !activeSink.compareAndSet(token, 0L)) return
        activeRenderer.set(0L)
        executor.execute {
            if (activeSink.get() != 0L) return@execute
            allowed = settings.enabled
            envelope = null; playback = null
            restoreEcarx(clearOriginal = true)
            if (!settings.enabled) restoreOriginal()
        }
    }

    private fun tick() {
        val values = settings
        if (ecarx != null) { ecarxTick(values); return }
        if (values.carRhythm) { carRhythm(); return }
        if (!allowed) return
        if (!values.enabled) { restoreOriginal(); return }
        if (context == null) return
        val nowMillis = android.os.SystemClock.uptimeMillis()
        val rendererActive = activeRenderer.get() != 0L && playback != null
        val playbackState = if (rendererActive) playback?.invoke() else null
        val playing = playbackState?.second == true && phonePlayback.current() != false
        if (playing) idleSinceMillis = null
        else if (idleSinceMillis == null) idleSinceMillis = nowMillis
        val effectiveBrightness = if (isNightNow()) values.nightBrightness else values.brightness
        val target = AmbientMusicActivityPolicy.target(
            enabled = values.enabled,
            music = values.music,
            brightness = effectiveBrightness,
            rendererActive = rendererActive,
            playing = playing,
            idle = values.idle,
            idleMillis = idleSinceMillis?.let { nowMillis - it } ?: 0,
        )
        if (target == AmbientMusicTarget.RESTORE_OEM) { restoreOriginal(); return }
        lampSession.setEnabled(true)
        val energy = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK) {
            val (head, headPlaying) = playbackState ?: return
            envelope?.playedWindow(head, headPlaying) ?: AmbientMusicEnvelope.Energy(0.0, 0.0)
        } else AmbientMusicEnvelope.Energy(0.0, 0.0)
        val rms = energy.rms
        val brightness = when (target) {
            AmbientMusicTarget.LOWEST -> 1
            AmbientMusicTarget.FOLLOW_PLAYBACK -> brightnessEnvelope.level(rms, nowMillis, effectiveBrightness)
            else -> effectiveBrightness
        }
        val area = if (target == AmbientMusicTarget.LOWEST) 3 else values.area
        val color = if (target == AmbientMusicTarget.FOLLOW_PLAYBACK && values.colorCycle)
            colors.color(values.colorMode, nowMillis, rms, energy.bassRms) else if (values.colorSource == AmbientColorSource.ALBUM) albumColor ?: 1
            else values.selectedColors.firstOrNull() ?: values.color
        lampSession.update(Triple(area, color, brightness))
    }

    private fun restoreOriginal() { lampSession.setEnabled(false) }

    /**
     * Night brightness applies when the head unit reports dark uiMode (most units follow the
     * headlight switch) or, for units that never change uiMode, between 19:00 and 07:00.
     */
    private fun isNightNow(): Boolean {
        val uiMode = context?.resources?.configuration?.uiMode ?: 0
        val uiNight = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (uiNight) return true
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < DAY_START_HOUR
    }

    // --- ECARX/Geely lamps -------------------------------------------------

    /**
     * The same 20 Hz analysis drives the car's 24-bit RGB zones. Colours follow the played
     * spectrum continuously; brightness follows the played RMS with the chosen attack/release.
     */
    private fun ecarxTick(values: AmbientMusicSettings.Values) {
        val client = ecarx ?: return
        if (!values.enabled) { restoreEcarx(clearOriginal = true); return }
        val nowMillis = android.os.SystemClock.uptimeMillis()
        val rendererActive = activeRenderer.get() != 0L && playback != null
        val playbackState = if (rendererActive) playback?.invoke() else null
        val playing = playbackState?.second == true && phonePlayback.current() != false
        if (playing) idleSinceMillis = null
        else if (idleSinceMillis == null) idleSinceMillis = nowMillis
        val level = EcarxAmbientLightPolicy.brightnessLevel(
            values.brightness, values.nightBrightness, isNightNow(),
        )
        val target = AmbientMusicActivityPolicy.target(
            enabled = values.enabled,
            music = values.music,
            brightness = level,
            rendererActive = rendererActive,
            playing = playing,
            idle = values.idle,
            idleMillis = idleSinceMillis?.let { nowMillis - it } ?: 0,
        )
        if (target == AmbientMusicTarget.RESTORE_OEM) { restoreEcarx(clearOriginal = false); return }

        if (!ecarxActive) {
            if (nowMillis < ecarxRetryAt) return
            val original = runCatching { client.begin(ecarxOriginal) }.getOrNull()
            if (original == null) { scheduleEcarxRetry(nowMillis); return }
            ecarxOriginal = original
            ecarxActive = true
            ecarxFailures = 0
            ecarxLast = null
            ecarxCadence.reset()
            beatModel.reset()
            Log.i("DiPlay-Ambient", "ECARX lamp session ready")
        }
        val ceiling = EcarxAmbientLightPolicy.ceilingPercent(level)
        val zone = client.zoneForArea(values.area)
        val hue = ecarxHue ?: EcarxAmbientSpectrum.staticHue(values.color)
        var color: Int
        var brightness: Int
        when (target) {
            AmbientMusicTarget.LOWEST -> {
                color = EcarxAmbientSpectrum.rgb(hue, 0.75)
                brightness = EcarxAmbientLightPolicy.MIN_BRIGHTNESS
            }
            AmbientMusicTarget.FOLLOW_PLAYBACK -> {
                val (head, headPlaying) = playbackState ?: return
                val bands = envelope?.playedBands(head, headPlaying)
                val pulse = if (bands != null) beatModel.update(bands, nowMillis) else beatModel.idle(nowMillis)
                val spectral = bands?.let(EcarxAmbientSpectrum::hue)
                val targetHue = pulse.hue
                    ?: spectral?.takeIf { nowMillis - pulse.lastEventAt >= SPECTRAL_FALLBACK_MILLIS }
                if (targetHue != null) {
                    val elapsed = if (ecarxHueAt == 0L) 0L else (nowMillis - ecarxHueAt).coerceAtLeast(0)
                    ecarxHue = EcarxAmbientSpectrum.smooth(ecarxHue ?: hue, targetHue, elapsed)
                    ecarxHueAt = nowMillis
                }
                // A visible punch: peak on the drum, short hold, fast decay back to a faint base.
                val floorPercent = maxOf(
                    EcarxAmbientLightPolicy.MIN_BRIGHTNESS, (ceiling * 0.18).roundToInt(),
                )
                brightness = (floorPercent + pulse.level * (ceiling - floorPercent)).roundToInt()
                    .coerceIn(floorPercent, ceiling)
                color = EcarxAmbientSpectrum.rgb(ecarxHue ?: hue, 0.70 + 0.25 * pulse.level)
            }
            else -> {
                color = EcarxAmbientSpectrum.rgb(hue, 0.75)
                brightness = ceiling
            }
        }
        val desired = Triple(zone, color, brightness)
        if (desired == ecarxLast || !ecarxCadence.trySubmit(nowMillis)) return
        if (runCatching { client.apply(zone, color, brightness) }.getOrDefault(false)) {
            ecarxLast = desired
            appliedCount++
            if (nowMillis - lastLogMillis >= 5_000) {
                lastLogMillis = nowMillis
                Log.i("DiPlay-Ambient", "ecarx applied=$appliedCount zone=$zone " +
                    "color=#${"%06X".format(color)} brightness=$brightness hue=${(ecarxHue ?: hue).toInt()}")
            }
        } else {
            Log.w("DiPlay-Ambient", "ECARX lamp write failed zone=$zone")
            ecarxActive = false
            scheduleEcarxRetry(nowMillis)
        }
    }

    /** Restores the pre-control snapshot; [clearOriginal] ends the session instead of a pause. */
    private fun restoreEcarx(clearOriginal: Boolean) {
        if (!ecarxActive && (!clearOriginal || ecarxOriginal == null)) {
            if (clearOriginal) ecarxOriginal = null
            return
        }
        val snapshot = ecarxOriginal
        ecarxActive = false
        ecarxLast = null
        ecarxCadence.reset()
        if (snapshot != null) runCatching { ecarx?.restore(snapshot) }
        if (clearOriginal) ecarxOriginal = null
    }

    private fun scheduleEcarxRetry(nowMillis: Long) {
        ecarxFailures = (ecarxFailures + 1).coerceAtMost(4)
        ecarxRetryAt = nowMillis + when (ecarxFailures) {
            1 -> 5_000L
            2 -> 10_000L
            3 -> 20_000L
            else -> 30_000L
        }
    }

    // The car's own rhythm follows CarPlay music while it plays; DiPlay does not set the lamps meanwhile. On head
    // units whose audio service makes no spectrum of its own, the feed option sends the played music's 16 bands.
    private var lastBandsMillis = 0L
    private fun carRhythm() {
        restoreOriginal()
        val app = context ?: return
        val rendererActive = activeRenderer.get() != 0L && playback != null
        val playbackState = if (rendererActive) playback?.invoke() else null
        val playing = carRhythmPlaying(rendererActive, playbackState?.second, phonePlayback.current())
        BydCarMusicRhythm.setPlaying(app, playing)
        if (!settings.carRhythmFeed) return
        val (head, headPlaying) = playbackState ?: return
        val now = android.os.SystemClock.uptimeMillis()
        // Not read between two sends: what plays then stays in the envelope's peak for the next send.
        if (playing && now - lastBandsMillis < BAND_INTERVAL_MILLIS) return
        val bands = envelope?.playedBands(head, playing && headPlaying)
        if (!playing || bands == null) return
        lastBandsMillis = now
        BydCarMusicRhythm.sendBands(app, bands)
    }
}
