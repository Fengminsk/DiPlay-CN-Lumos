package com.shilapi.xcertplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.WindowManager
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlaySafeArea
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.CarPlayUiScale
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayDeviceName
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.UsbDeviceId

/** The connection's immutable settings are built without creating a projection Activity. */
internal object CarPlaySessionConfig {
    data class Result(val runtime: CarPlayRuntimeConfig, val airPlay: AirPlayConfig, val display: CarPlaySessionDisplay)

    fun create(context: Context, width: Int, height: Int): Result {
        val identity = AirPlayPersistence.loadIdentity(context)
        val id = DiPlayBootstrap.deviceId(identity)
        val wireless = AirPlayPersistence.loadWirelessEnabled(context)
        val mode = AirPlayPersistence.loadWirelessHotspotMode(context)
        val mfi = AirPlayPersistence.loadMfiTarget(context)
        val ssid = AirPlayPersistence.loadManualHotspotSsid(context)
        val name = CarPlayDeviceName.fromHotspot(
            CarPlayNameSettings.useHotspotName(context),
            ssid.takeIf { mode == WirelessHotspotMode.MANUAL },
        )
        val manufacturer = AirPlayPersistence.loadManufacturer(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }
        val model = AirPlayPersistence.loadModel(context).trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }
        val location = AirPlayPersistence.loadLocationReportingEnabled(context) &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val runtime = CarPlayRuntimeConfig(
            mfiTarget = mfi,
            ch341Devices = if (mfi == MfiTarget.USB_CH341) listOf(UsbDeviceId(0x1a86, 0x5512)) else emptyList(),
            linuxI2cPath = if (mfi == MfiTarget.I2C) AirPlayPersistence.loadMfiI2cPath(context).trim() else null,
            remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(context).trim().ifEmpty { null },
            remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(context).ifEmpty { null },
            identification = Iap2IdentificationConfig(
                name = name,
                modelIdentifier = model,
                manufacturer = manufacturer,
                serialNumber = "DIPLAY-" + id.replace(":", ""),
                firmwareVersion = "0.1.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = location,
                vehicleStatusEnabled = !Lynk09Settings.instrumentToIphone(context) &&
                    com.shilapi.xcertplay.hud.BydOutputSettings.batteryToIphoneActive(context),
                chargingConnectors = com.shilapi.xcertplay.hud.BydOutputSettings.chargingConnectors(context),
                vehicleSpeedEnabled = location &&
                    (if (Lynk09Settings.instrumentToIphone(context)) Lynk09Settings.instrumentToIphoneActive(context)
                    else com.shilapi.xcertplay.hud.BydOutputSettings.wheelSpeedToIphoneActive(context)),
            ),
            label = "DiPlay",
            hostName = "diplay-" + id.replace(":", "").lowercase(),
            hostMac = id.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(context),
            transport = if (wireless) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
            wirelessHotspotMode = mode,
            wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(context),
            manualHotspotSsid = ssid,
            manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
            manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(context),
            manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(context),
            manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
            existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(context),
            existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(context),
            useHotspotNameForDevice = CarPlayNameSettings.useHotspotName(context),
            locationReportingEnabled = location,
        )
        val safeWidth = (width.coerceAtLeast(2) / 2) * 2
        val safeHeight = (height.coerceAtLeast(2) / 2) * 2
        val maximum = AirPlayPersistence.loadMaximumDetectedDisplay(context)
        val physical = AirPlayDisplaySettings.resolvePhysicalSizeMm(
            safeWidth, safeHeight, maxOf(maximum.first, safeWidth), maxOf(maximum.second, safeHeight),
            AirPlayPersistence.loadWidthPhysicalMm(context), AirPlayPersistence.loadPhysicalSizeBasis(context),
        )
        val base = AirPlayDisplayConfig(
            safeWidth, safeHeight, physical.widthMm, physical.heightMm,
            AirPlayPersistence.loadFps(context),
            if (AndroidTvInputMode.shouldUseKnobAsPrimaryInput(context)) 3 else 1,
        )
        val percent = AirPlayPersistence.loadDisplayScalePercent(context)
        val requested = CarPlayUiScale.apply(CarPlayDisplayScale.applyPercent(base, percent), CarPlayUiScale.DEFAULT)
        val scaled = if (percent > 100 && !decoderSupports(requested, AirPlayPersistence.loadHevcEnabled(context),
                Build.VERSION.SDK_INT >= 29 && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context))) {
            AirPlayPersistence.saveDisplayScalePercent(context, 100)
            AirPlayPersistence.saveDisplayScaleTenths(context, CarPlayDisplayScale.DEFAULT_TENTHS)
            base
        } else requested
        val main = scaled.copy(
            safeArea = AirPlaySafeArea.toInsets(
                AirPlayPersistence.loadSafeAreaRect(context, width, height), width, height,
                scaled.widthPixels, scaled.heightPixels,
            ),
            safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(context),
        )
        @Suppress("DEPRECATION")
        val screen = android.util.DisplayMetrics().also {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.getRealMetrics(it)
        }
        val screenWidth = screen.widthPixels.takeIf { it > 0 } ?: width
        val screenHeight = screen.heightPixels.takeIf { it > 0 } ?: height
        val longPixels = maxOf(main.widthPixels, main.heightPixels)
        val square = if (CarPlayRotation.enabled(context)) CarPlayRotation.squareSide(
            longPixels, CarPlayRotation.picture(context), AirPlayPersistence.loadHevcEnabled(context),
            Build.VERSION.SDK_INT >= 29 && AirPlayPersistence.loadHevcSoftwareDecoderEnabled(context),
        ) else null
        val canvas = if (square == null) main else main.copy(
            widthPixels = square, heightPixels = square,
            widthPhysicalMm = if (main.widthPixels >= main.heightPixels) main.widthPhysicalMm else main.heightPhysicalMm,
            heightPhysicalMm = if (main.widthPixels >= main.heightPixels) main.widthPhysicalMm else main.heightPhysicalMm,
            safeArea = null,
        )
        val screens = if (square == null) listOf(CarPlayViewAreas.Screen(
            main.widthPixels, main.heightPixels, main.heightPixels > main.widthPixels,
        )) else CarPlayRotation.turningAreas(square, width, height, screenWidth, screenHeight).let { (landscape, portrait) ->
            listOf(CarPlayViewAreas.Screen(landscape.first, landscape.second, false),
                CarPlayViewAreas.Screen(portrait.first, portrait.second, true))
        }
        val (landscapeWindow, portraitWindow) = CarPlayRotation.turnedWindows(
            width, height, screenWidth, screenHeight)
        fun systemDimension(name: String): Int {
            val id = context.resources.getIdentifier(name, "dimen", "android")
            return if (id != 0) runCatching { context.resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
        }
        val screenLong = maxOf(screenWidth, screenHeight)
        val screenShort = minOf(screenWidth, screenHeight)
        val divider = (systemDimension("docked_stack_divider_thickness") -
            2 * systemDimension("docked_stack_divider_insets")).coerceAtLeast(0)
        val splitWindow: (Boolean) -> Pair<Float, Float>? = { portrait ->
            if (!SplitScreenSettings.enabled(context)) null else {
                val window = if (portrait) portraitWindow else landscapeWindow
                val expected = SplitScreenSettings.expectedWindow(
                    portrait, screenLong, screenShort,
                    systemDimension("status_bar_height"), systemDimension("navigation_bar_height"), divider)
                SplitScreenSettings.ofWindow(
                    SplitScreenSettings.window(context, portrait, expected),
                    if (portrait) screenShort else screenLong,
                    if (portrait) screenLong else screenShort,
                    window.first, window.second)
            }
        }
        val viewAreas = CarPlayViewAreas.build(
            canvas.widthPixels, canvas.heightPixels, screens, CarPlayDock.load(context),
            splitWindow = splitWindow, startPortrait = height > width,
            sidePanel = SidePanelSettings.enabled(context),
            rightHandDrive = AirPlayPersistence.loadRightHandDrive(context),
        )
        val declared = if (viewAreas == null) canvas else canvas.copy(
            viewAreas = viewAreas.areas, initialViewArea = viewAreas.current,
        )
        fun validIcon(bytes: ByteArray): Boolean {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            return options.outWidth > 0 && options.outWidth == options.outHeight
        }
        val customIcon = AirPlayPersistence.loadCustomAirPlayIconFile(context)?.let { runCatching { it.readBytes() }.getOrNull() }
        val iconBytes = customIcon?.takeIf(::validIcon)
            ?: context.resources.openRawResource(CarButtonDefaults.iconResource(context)).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(iconBytes, 0, iconBytes.size, bounds)
        val icon = AirPlayIcon(bounds.outWidth, bounds.outHeight, iconBytes)
        val cluster = clusterConfig(context)
        cluster?.let { MapMirrors.streamAspect = it.widthPixels.toDouble() / it.heightPixels }
        val airPlay = AirPlayConfig(
            deviceName = name, deviceId = id,
            btMac = DiPlayBluetooth.localAddress(context) ?: id,
            sourceVersion = "950.7.1", main = declared, cluster = cluster,
            rightHandDrive = AirPlayPersistence.loadRightHandDrive(context),
            hevc = AirPlayPersistence.loadHevcEnabled(context),
            microphone = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            microphoneOpus = com.shilapi.xcertplay.media.OpusEncoderSupport.isAvailable(),
            manufacturer = manufacturer, model = model,
            oemLabel = CarButtonDefaults.resolveLabel(context, AirPlayPersistence.loadOemLabel(context)),
            icons = listOf(icon),
            videoInCar = com.shilapi.xcertplay.hud.BydOutputSettings.videoWhileParkedActive(context),
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(context),
            disableAudioOutput = AirPlayPersistence.loadCarBluetoothAudio(context),
        )
        @Suppress("DEPRECATION")
        val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: 0
        return Result(runtime, airPlay, CarPlaySessionDisplay(
            declared.widthPixels, declared.heightPixels, rotation,
            AirPlayPersistence.loadHideTopBar(context), AirPlayPersistence.loadHideBottomBar(context),
            width, height, viewAreas,
        ))
    }

    private fun clusterConfig(context: Context): AirPlayDisplayConfig? {
        if (!AirPlayPersistence.loadClusterMapEnabled(context)) return null
        val content = AirPlayPersistence.loadClusterContent(context)
        val fixed = {
            DiLink4ClusterDisplay.streamConfig(content,
                AirPlayPersistence.loadClusterMarkerHorizontalStep(context),
                AirPlayPersistence.loadClusterMarkerVerticalStep(context),
                AirPlayPersistence.loadClusterSafeAreaRect(context))
        }
        if (AdbClusterRouter.enabled(context) && ClusterActivityOutput.hasConfirmedRoute()) return fixed()
        val theme = DiLink51ClusterLayout.theme(context)
        val display = ClusterMapPresentation.findDisplay(context, theme)
        if (display != null) {
            val size = ClusterMapPresentation.sizeOf(display)
            if (size.x > 0 && size.y > 0) {
                if (DiLink51ClusterLayout.supported() &&
                    DiLink51ClusterLayout.plan(size.x, size.y, theme) != null) return DiLink51ClusterLayout.streamConfig()
                if (DiLink4ClusterDisplay.matches(display.name, size.x, size.y)) return fixed()
                val smallWindow = AirPlayPersistence.loadClusterSmallWindowMode(context) == 1
                return CarPlayClusterDisplay.config(
                    size.x, size.y, AirPlayPersistence.loadClusterMapScalePercent(context), 0, 0,
                    content,
                    markerXPercent = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowMarkerXPercent(context)
                        else AirPlayPersistence.loadClusterMarkerXPercent(context),
                    markerYPercent = if (smallWindow) AirPlayPersistence.loadClusterSmallWindowMarkerYPercent(context)
                        else AirPlayPersistence.loadClusterMarkerYPercent(context),
                )
            }
        }
        return CarPlayClusterDisplay.config(
            widthPixels = 1280, heightPixels = 720, scalePercent = 100,
            content = content, baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT,
        )
    }

    private fun decoderSupports(display: AirPlayDisplayConfig, hevc: Boolean, softwareHevc: Boolean): Boolean {
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 ||
            minOf(display.widthPixels, display.heightPixels) > 2160 || softwareHevc) return false
        return runCatching {
            val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
                !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
            } ?: return@runCatching false
            val hardware = if (Build.VERSION.SDK_INT >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            hardware && decoder.getCapabilitiesForType(mime).videoCapabilities?.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble()) == true
        }.getOrDefault(false)
    }
}
