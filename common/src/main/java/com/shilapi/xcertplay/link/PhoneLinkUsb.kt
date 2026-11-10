package com.shilapi.xcertplay.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable

/**
 * Watches for phone attachments while the DiPlay screen is in front and hands them to
 * [PhoneLinkRouter].
 *
 * Both a receiver and a poller are used on purpose. The receiver is the cheap path, but some
 * vendor USB stacks never deliver `USB_DEVICE_ATTACHED` to a third-party runtime receiver unless
 * the app also declares a device filter in its manifest. The poller reads
 * `UsbManager.deviceList` (no permission needed to enumerate), so a phone that is already plugged
 * in, or that the ROM fails to announce, is still picked up within a second or two.
 */
object PhoneLinkUsb {

    private const val POLL_INTERVAL_MS = 1500L

    fun attach(context: Context): Closeable {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                LinkLog.append(app, "usb broadcast ${intent.action}")
                PhoneLinkRouter.onUsbIntent(context, intent)
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }

        var running = true
        val poller = Thread({
            val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
            var seen = emptySet<String>()
            while (running) {
                val devices = runCatching { usb.deviceList.values.toList() }.getOrDefault(emptyList())
                val keys = devices.associateBy { keyOf(it) }
                if (keys.keys != seen) {
                    LinkLog.append(
                        app,
                        "usb scan: " + devices.joinToString { "${it.vendorId.toString(16)}:${it.productId.toString(16)}" },
                    )
                }
                devices.filter { keyOf(it) !in seen }.forEach {
                    runCatching { PhoneLinkRouter.onUsbAttached(app, it) }
                }
                seen = keys.keys
                Thread.sleep(POLL_INTERVAL_MS)
            }
        }, "PhoneLinkUsb").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }

        return Closeable {
            running = false
            poller.interrupt()
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private fun keyOf(device: UsbDevice): String = "${device.deviceId}:${device.vendorId}:${device.productId}"
}
