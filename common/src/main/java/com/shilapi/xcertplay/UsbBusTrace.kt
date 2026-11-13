package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.Closeable

/** Records USB enumeration independently of the home screen while CarPlay owns the foreground Service. */
internal class UsbBusTrace(context: Context) : Closeable {
    private val app = context.applicationContext
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    private var lastState: String? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event = when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> "attached"
                UsbManager.ACTION_USB_DEVICE_DETACHED -> "detached"
                else -> return
            }
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            CarPlayConnectionTrace.append(app,
                "usb broadcast=$event vidpid=${device?.let(::vidpid) ?: "unknown"} " +
                    "id=${device?.deviceId ?: -1} elapsedMs=${SystemClock.elapsedRealtime()}")
            snapshot("broadcast")
        }
    }
    private val poller = object : Runnable {
        override fun run() {
            if (closed) return
            snapshot("poll", changesOnly = true)
            main.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        snapshot("start")
        main.postDelayed(poller, POLL_INTERVAL_MS)
    }

    @Synchronized fun snapshot(reason: String, changesOnly: Boolean = false) {
        if (closed) return
        val state = runCatching {
            usb.deviceList.values.sortedBy { it.deviceId }.joinToString(",") {
                "${vidpid(it)}#${it.deviceId}/permission=${usb.hasPermission(it)}"
            }.ifEmpty { "empty" }
        }.getOrElse { "error=${it.javaClass.simpleName}" }
        if (!changesOnly || state != lastState) {
            CarPlayConnectionTrace.append(app,
                "usb bus reason=$reason state=$state elapsedMs=${SystemClock.elapsedRealtime()}")
        }
        lastState = state
    }

    override fun close() {
        closed = true
        main.removeCallbacks(poller)
        runCatching { app.unregisterReceiver(receiver) }
    }

    private fun vidpid(device: UsbDevice) =
        "${device.vendorId.toString(16)}:${device.productId.toString(16)}"

    private companion object { const val POLL_INTERVAL_MS = 1_000L }
}
