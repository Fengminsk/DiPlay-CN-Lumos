package com.shilapi.xcertplay.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val watchers = AtomicInteger()

    fun isWatching(): Boolean = watchers.get() > 0

    fun attach(context: Context): Closeable {
        val app = context.applicationContext
        // The broadcast and poller can report the same enumeration. Claim it once before routing,
        // so one grant cannot start the SDK and immediately re-arm its scan from a second callback.
        val seen = ConcurrentHashMap.newKeySet<String>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                LinkLog.append(app, "usb broadcast ${intent.action}")
                PhoneLinkRouter.attachedDevice(intent)?.let { device ->
                    if (synchronized(seen) { seen.add(keyOf(device)) }) {
                        PhoneLinkRouter.onUsbAttached(context, device)
                    }
                }
            }
        }
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }
        watchers.incrementAndGet()

        val running = AtomicBoolean(true)
        val poller = Thread({
            val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
            while (running.get()) {
                val (devices, changes) = synchronized(seen) {
                    val devices = runCatching { usb.deviceList.values.toList() }.getOrDefault(emptyList())
                    val keys = devices.associateBy { keyOf(it) }
                    val changed = keys.keys != seen
                    val unplugged = devices.isEmpty() && seen.isNotEmpty()
                    seen.retainAll(keys.keys)
                    devices to Triple(changed, unplugged, devices.filter { seen.add(keyOf(it)) })
                }
                val (changed, unplugged, newDevices) = changes
                if (changed) {
                    LinkLog.append(
                        app,
                        "usb scan: " + devices.joinToString { "${it.vendorId.toString(16)}:${it.productId.toString(16)}" },
                    )
                }
                if (unplugged) {
                    // Back to a bare bus: the next plug is a new permission cycle.
                    UsbPermissionBroker.resetForUnplug()
                }
                newDevices.forEach {
                    runCatching { PhoneLinkRouter.onUsbAttached(app, it) }
                }
                try {
                    Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    // Closing the home screen wakes the poller; interruption is its normal exit.
                    break
                }
            }
        }, "PhoneLinkUsb").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }

        val closed = AtomicBoolean()
        return Closeable {
            if (!closed.compareAndSet(false, true)) return@Closeable
            running.set(false)
            poller.interrupt()
            runCatching { app.unregisterReceiver(receiver) }
            watchers.decrementAndGet()
        }
    }

    private fun keyOf(device: UsbDevice): String = "${device.deviceId}:${device.vendorId}:${device.productId}"
}
