package com.shilapi.xcertplay.link

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.IntentCompat
import com.shilapi.xcertplay.CarPlayHostActivity
import com.shilapi.xcertplay.icoca.IcocaEngine

/**
 * Routes an attached phone to the projection engine the driver selected.
 *
 * This is the auto-launch path: plugging a phone no longer needs a second tap, but it never
 * switches engines on its own — [PhoneLinkPolicy] only fires when the detected phone matches the
 * saved choice.
 */
object PhoneLinkRouter {

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
        return PhoneLinkPolicy.decide(kind, PhoneLinkSettings.loadSource(context))
    }

    /** Applies the decision and returns it, so callers (and tests) can see what was chosen. */
    fun onUsbAttached(context: Context, device: UsbDevice): LinkAction {
        val kind = UsbPhoneClassifier.classify(signature(device))
        val source = PhoneLinkSettings.loadSource(context)
        val action = PhoneLinkPolicy.decide(kind, source)
        Log.i(TAG, "attached vid=0x${device.vendorId.toString(16)} kind=$kind source=$source -> $action")
        when (action) {
            LinkAction.START_CARPLAY -> context.startActivity(
                Intent(context, CarPlayHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            LinkAction.START_ICCOA -> {
                IcocaEngine.start(context)
                requestUsbAccess(context, device)
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
     * Prompts for USB permission on an attached phone and re-arms the wired scan afterwards.
     *
     * Without this the ICCOA SDK never sees the device: it requires `UsbManager.hasPermission()`
     * and its own automatic grant needs the signature-level `MANAGE_USB`.
     */
    fun requestUsbAccess(context: Context, device: UsbDevice) {
        UsbPermissionBroker.request(context, device) { granted ->
            if (granted) {
                IcocaEngine.refreshUsb(context)
            } else {
                Log.w(TAG, "USB permission refused for vid=0x${device.vendorId.toString(16)}")
            }
        }
    }

    /**
     * Asks for permission on phones that were plugged in before ICCOA was selected, so switching
     * the source does not require re-plugging the cable.
     */
    fun requestUsbAccessForAttachedPhones(context: Context) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        usb.deviceList.values
            .firstOrNull { it.vendorId != UsbPhoneClassifier.APPLE_VENDOR_ID }
            ?.let { requestUsbAccess(context, it) }
    }

    /** Convenience for intent-driven call sites. */
    fun onUsbIntent(context: Context, intent: Intent?): LinkAction? =
        attachedDevice(intent)?.let { onUsbAttached(context, it) }
}
