package com.shilapi.xcertplay.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Asks the system for permission to open a USB device.
 *
 * The ICCOA SDK's AOA path cannot work without this: `AOAHostSetup.scanUsbDevices()` only accepts
 * devices where `UsbManager.hasPermission()` is already true, and its fallback
 * (`IUsbManager.grantDevicePermission`) needs the signature-level `MANAGE_USB`. The SDK never shows
 * the permission dialog itself, and its scan thread just keeps polling, so granting permission
 * here is enough for the next poll to pick the device up.
 */
object UsbPermissionBroker {

    private const val TAG = "DiPlay/UsbPermission"
    private const val ACTION = "com.shilapi.xcertplay.USB_DEVICE_PERMISSION"
    private const val EXTRA_DEVICE_ID = "device_id"

    private val pending = ConcurrentHashMap<Int, (Boolean) -> Unit>()

    @Volatile
    private var receiver: BroadcastReceiver? = null

    fun has(context: Context, device: UsbDevice): Boolean =
        usbManager(context).hasPermission(device)

    /**
     * Returns the grant through [onResult]. The callback runs on the main thread; a device that is
     * already permitted answers immediately.
     */
    fun request(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val manager = usbManager(app)
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        ensureReceiver(app)
        pending[device.deviceId] = onResult
        val base = Intent(ACTION)
            .setPackage(app.packageName)
            .putExtra(EXTRA_DEVICE_ID, device.deviceId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        manager.requestPermission(device, PendingIntent.getBroadcast(app, device.deviceId, base, flags))
    }

    private fun ensureReceiver(app: Context) {
        if (receiver != null) return
        synchronized(this) {
            if (receiver != null) return
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.i(TAG, "device $deviceId permission granted=$granted")
                    pending.remove(deviceId)?.invoke(granted)
                }
            }
            ContextCompat.registerReceiver(
                app,
                created,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = created
        }
    }

    private fun usbManager(context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager
}
