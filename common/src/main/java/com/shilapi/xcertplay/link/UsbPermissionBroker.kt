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
 * the permission dialog itself, and its scan thread treats a missing grant as fatal, so the grant
 * has to reach us before the device is opened.
 *
 * Callers that ask while a prompt for the same device is already in flight are queued instead of
 * being answered with a made-up "false": deduplicating by replying early silently disabled the
 * whole path once, because the caller started the engine from that answer.
 */
object UsbPermissionBroker {

    private const val TAG = "DiPlay/UsbPermission"
    private const val ACTION = "com.shilapi.xcertplay.USB_DEVICE_PERMISSION"
    private const val EXTRA_DEVICE_ID = "device_id"
    private const val EXTRA_DEVICE_KEY = "device_key"

    /** Prompts allowed per process for one vendor/product pair (AOA re-enumeration needs two). */
    private const val MAX_PROMPTS_PER_DEVICE = 3

    private val waiting = ConcurrentHashMap<String, MutableList<(Boolean) -> Unit>>()
    private val prompts = ConcurrentHashMap<String, Int>()

    @Volatile
    private var receiver: BroadcastReceiver? = null

    fun has(context: Context, device: UsbDevice): Boolean =
        usbManager(context).hasPermission(device)

    /**
     * Returns the grant through [onResult]. The callback runs on the main thread; an already
     * permitted device answers immediately, and every caller is answered exactly once.
     */
    fun request(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val manager = usbManager(app)
        if (manager.hasPermission(device)) {
            onResult(true)
            return
        }
        val key = "${device.vendorId}:${device.productId}"
        synchronized(this) {
            waiting[key]?.let {
                it.add(onResult)
                return
            }
            val promptsUsed = prompts[key] ?: 0
            if (promptsUsed >= MAX_PROMPTS_PER_DEVICE) {
                onResult(false)
                return
            }
            prompts[key] = promptsUsed + 1
            waiting[key] = mutableListOf(onResult)
        }
        ensureReceiver(app)
        val base = Intent(ACTION)
            .setPackage(app.packageName)
            .putExtra(EXTRA_DEVICE_ID, device.deviceId)
            .putExtra(EXTRA_DEVICE_KEY, key)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        manager.requestPermission(device, PendingIntent.getBroadcast(app, device.deviceId, base, flags))
    }

    private fun ensureReceiver(app: Context) {
        if (receiver != null) return
        synchronized(this) {
            if (receiver != null) return
            val created = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val key = intent.getStringExtra(EXTRA_DEVICE_KEY)
                        ?: intent.getIntExtra(EXTRA_DEVICE_ID, -1).toString()
                    Log.i(TAG, "device $key permission granted=$granted")
                    val callbacks = synchronized(this@UsbPermissionBroker) { waiting.remove(key) }
                    callbacks?.forEach { it(granted) }
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
