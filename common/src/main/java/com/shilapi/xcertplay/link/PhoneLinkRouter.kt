package com.shilapi.xcertplay.link

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.IntentCompat
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.IcocaSettings
import com.andrerinas.openheadunit.connection.IcocaBridge
import com.shilapi.xcertplay.icoca.IcocaEngine

/**
 * Routes an attached phone to the projection engine the driver selected.
 *
 * This is the auto-launch path: plugging a phone no longer needs a second tap, but it never
 * switches engines on its own — [PhoneLinkPolicy] only fires when the detected phone matches the
 * saved choice.
 */
object PhoneLinkRouter {

    const val EXTRA_CARPLAY_USB_ATTACHED = "carplay_usb_attached"

    private const val TAG = "DiPlay/PhoneLink"

    /** Reads the attached device out of a `USB_DEVICE_ATTACHED` intent. */
    fun attachedDevice(intent: Intent?): UsbDevice? {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
        return IntentCompat.getParcelableExtra(
            intent,
            UsbManager.EXTRA_DEVICE,
            UsbDevice::class.java,
        )
    }

    fun signature(device: UsbDevice): UsbPhoneSignature = UsbPhoneSignature(
        vendorId = device.vendorId,
        productId = device.productId,
        interfaces = (0 until device.interfaceCount).map { index ->
            val descriptor = device.getInterface(index)
            UsbPhoneSignature.UsbInterfaceSignature(
                interfaceClass = descriptor.interfaceClass,
                subClass = descriptor.interfaceSubclass,
                protocol = descriptor.interfaceProtocol,
            )
        },
    )

    /** What would happen for this device, without doing it. */
    fun decide(context: Context, device: UsbDevice): LinkAction {
        val kind = UsbPhoneClassifier.classify(signature(device))
        return PhoneLinkPolicy.decide(kind)
    }

    /** Applies the decision and returns it, so callers (and tests) can see what was chosen. */
    fun onUsbAttached(context: Context, device: UsbDevice): LinkAction {
        val kind = UsbPhoneClassifier.classify(signature(device))
        val source = PhoneLinkSettings.loadSource(context)
        val action = PhoneLinkPolicy.decide(kind)
        Log.i(TAG, "attached vid=0x${device.vendorId.toString(16)} kind=$kind source=$source -> $action")
        LinkLog.append(
            context,
            "attached vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                "kind=$kind source=$source action=$action",
        )
        when (action) {
            LinkAction.START_CARPLAY -> if (!CarPlayBackgroundSession.hasSession() ||
                AirPlayPersistence.loadWirelessEnabled(context)) {
                context.startActivity(Intent(context, DiPlayActivity::class.java)
                    .putExtra(EXTRA_CARPLAY_USB_ATTACHED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }

            LinkAction.START_ICCOA -> {
                LinkLog.startCapture(context)
                startIccoa(
                    context,
                    wiredOnly = IcocaSettings.usb(context) && !IcocaSettings.wireless(context),
                    askPermission = true,
                )
            }

            LinkAction.IGNORE -> if (PhoneLinkPolicy.needsUsbAccess(kind, source)) {
                // The phone may still be in charging-only mode; the AOA handshake can switch it,
                // but only once the app is allowed to open the device.
                requestUsbAccess(context, device)
            }
        }
        return action
    }

    /**
     * Starts the engine, but in wired mode only after the phone's USB permission is granted.
     *
     * The order is not cosmetic. The SDK's `AOAHostSetup.isPotentialMobileDevice()` calls
     * `UsbDevice.getSerialNumber()` *before* it checks `hasPermission`, and that throws
     * `SecurityException` when permission is missing. The scan thread treats any exception as fatal
     * and returns, so a grant that arrives afterwards can never revive it — the engine has to be
     * started with the permission already in hand.
     */
    fun startIccoa(
        context: Context,
        wiredOnly: Boolean,
        askPermission: Boolean = false,
        retryIfStarted: Boolean = false,
        onStarted: () -> Unit = {},
    ) {
        val start = {
            val engine = IcocaEngine.bridge(context)
            val active = engine.state.value.phase == IcocaBridge.Phase.Connected
            // An earlier wired attempt leaves the process-wide SDK in USB-only mode. A later
            // wireless tap must re-enter start() so it clears that mode and starts BLE advertising.
            if (shouldStartIccoa(engine.isStarted, active, engine.manager.isUsbOnly,
                    wiredOnly, retryIfStarted)) {
                IcocaEngine.start(context, wiredOnly)
            }
            onStarted()
        }
        if (!wiredOnly || !askPermission) {
            start()
            return
        }
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val attached = usb.deviceList.values.toList()
        LinkLog.append(context, "wired: USB devices=" + attached.joinToString { device ->
            val interfaces = runCatching {
                (0 until device.interfaceCount).joinToString("/") { index ->
                    device.getInterface(index).let { "${it.interfaceClass}:${it.interfaceSubclass}:${it.interfaceProtocol}" }
                }
            }.getOrDefault("unavailable")
            "${device.vendorId.toString(16)}:${device.productId.toString(16)}[$interfaces]"
        })
        val device = attached
            .firstOrNull { UsbPhoneClassifier.classify(signature(it)) == PhoneKind.ANDROID }
            ?: attached.singleOrNull()?.takeIf {
                UsbPhoneClassifier.possibleChargingOnlyAndroid(signature(it))
            }
        if (device == null) {
            LinkLog.append(context, "wired: no phone attached yet, waiting for the attach event")
            start()
            return
        }
        if (UsbPhoneClassifier.classify(signature(device)) == PhoneKind.UNKNOWN) {
            LinkLog.append(context, "wired: trying sole vendor-specific device in charging-only mode")
        }
        if (UsbPermissionBroker.has(context, device)) {
            LinkLog.append(context, "wired: permission already held")
            val alreadyStarted = IcocaEngine.isStarted(context)
            start()
            if (alreadyStarted) IcocaEngine.refreshUsb(context)
            return
        }
        LinkLog.append(context, "wired: asking for USB permission")
        UsbPermissionBroker.request(context, device) { granted ->
            LinkLog.append(context, "wired: permission granted=$granted")
            if (granted) {
                if (!IcocaSettings.usb(context) || IcocaSettings.wireless(context)) {
                    LinkLog.append(context, "wired: permission arrived after transport changed; ignoring old request")
                    return@request
                }
                val alreadyStarted = IcocaEngine.isStarted(context)
                start()
                if (alreadyStarted) IcocaEngine.refreshUsb(context)
            }
        }
    }

    /**
     * Prompts for USB permission on an attached phone and re-arms the wired scan afterwards.
     *
     * Without this the ICCOA SDK never sees the device: it requires `UsbManager.hasPermission()`
     * and its own automatic grant needs the signature-level `MANAGE_USB`.
     */
    fun requestUsbAccess(context: Context, device: UsbDevice, onResult: ((Boolean) -> Unit)? = null) {
        LinkLog.append(context, "requesting USB permission for vid=0x${device.vendorId.toString(16)}")
        UsbPermissionBroker.request(context, device) { granted ->
            LinkLog.append(context, "USB permission granted=$granted")
            if (granted) {
                IcocaEngine.refreshUsb(context)
            } else {
                Log.w(TAG, "USB permission refused for vid=0x${device.vendorId.toString(16)}")
            }
            onResult?.invoke(granted)
        }
    }

    /**
     * Asks for permission on phones that were plugged in before ICCOA was selected, so switching
     * the source does not require re-plugging the cable.
     */
    fun requestUsbAccessForAttachedPhones(context: Context) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val attached = usb.deviceList.values.toList()
        LinkLog.append(
            context,
            "usb devices now: " + attached.joinToString { "vid=0x${it.vendorId.toString(16)}" },
        )
        attached.firstOrNull { it.vendorId != UsbPhoneClassifier.APPLE_VENDOR_ID }
            ?.let { requestUsbAccess(context, it) }
    }

    /** Convenience for intent-driven call sites. */
    fun onUsbIntent(context: Context, intent: Intent?): LinkAction? =
        attachedDevice(intent)?.let { onUsbAttached(context, it) }

    internal fun shouldStartIccoa(started: Boolean, connected: Boolean, currentWiredOnly: Boolean,
                                  requestedWiredOnly: Boolean, explicitRetry: Boolean): Boolean =
        !started || !connected && (currentWiredOnly != requestedWiredOnly || explicitRetry)
}
