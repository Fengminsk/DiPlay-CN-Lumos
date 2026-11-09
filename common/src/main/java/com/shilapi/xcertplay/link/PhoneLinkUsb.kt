package com.shilapi.xcertplay.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable

/**
 * Watches for phone attachments while the DiPlay screen is in front and hands them to
 * [PhoneLinkRouter].
 *
 * A runtime receiver is enough here: the attach has to be routed to the screen the driver is
 * looking at anyway, and a manifest receiver would also fire while a projection is running.
 */
object PhoneLinkUsb {

    fun attach(context: Context): Closeable {
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
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
        return Closeable { runCatching { app.unregisterReceiver(receiver) } }
    }
}
