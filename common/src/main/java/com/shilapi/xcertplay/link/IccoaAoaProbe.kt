package com.shilapi.xcertplay.link

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * One controlled AOA preflight before the ICCOA SDK gets the USB device.
 *
 * The bundled SDK sends request 52 with raw UTF-8 bytes. AOA requires a terminating zero byte.
 * We compare the phone's response to a correctly encoded prefix without sending request 53, so
 * this probe never switches the phone's USB mode. The SDK still owns the actual connection.
 */
internal object IccoaAoaProbe {
    private const val TIMEOUT_MS = 400
    private const val RETRY_PAUSE_MS = 150L
    private const val PROTOCOL_ATTEMPTS = 4
    private const val STRING_ATTEMPTS = 2
    private const val GOOGLE_VID = 0x18d1
    private val aoaPids = 0x2d00..0x2d05
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "iccoa-aoa-probe").apply { isDaemon = true }
    }
    private val pending = mutableMapOf<Int, MutableList<(Boolean) -> Unit>>()
    private val completed = mutableSetOf<Int>()

    /** Only the first caller for a device starts the SDK; simultaneous taps share the probe. */
    fun beforeSdk(context: Context, device: UsbDevice, onComplete: (startSdk: Boolean) -> Unit) {
        val app = context.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        if (device.vendorId == GOOGLE_VID && device.productId in aoaPids) {
            LinkLog.appendProbe(app, "AOA probe: already accessory mode; SDK takes over")
            onComplete(true)
            return
        }
        synchronized(this) {
            if (device.deviceId in completed) {
                LinkLog.appendProbe(app, "AOA probe: already completed for this enumeration id=${device.deviceId}")
                onComplete(true)
                return
            }
            pending[device.deviceId]?.let {
                it.add(onComplete)
                return
            }
            pending[device.deviceId] = mutableListOf(onComplete)
        }
        worker.execute {
            try {
                runProbe(app, usb, device)
            } catch (t: Throwable) {
                LinkLog.appendProbe(app, "AOA probe: exception=${t.javaClass.simpleName}")
            } finally {
                main.post {
                    val callbacks = synchronized(this) {
                        completed.add(device.deviceId)
                        pending.remove(device.deviceId).orEmpty()
                    }
                    callbacks.forEachIndexed { index, callback -> callback(index == 0) }
                }
            }
        }
    }

    private fun runProbe(context: Context, usb: UsbManager, device: UsbDevice) {
        val identity = "${device.vendorId.toString(16)}:${device.productId.toString(16)}"
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        LinkLog.appendProbe(context, "AOA probe: begin build=$versionName vid:pid=$identity id=${device.deviceId}")
        LinkLog.appendProbe(context, "AOA probe: initial ${usbState(usb, device)}")
        if (!usb.hasPermission(device)) {
            LinkLog.appendProbe(context, "AOA probe: skipped; USB permission absent")
            return
        }
        val openedAt = SystemClock.elapsedRealtime()
        val connection = usb.openDevice(device)
        LinkLog.appendProbe(context,
            "AOA probe: openDevice success=${connection != null} elapsedMs=${SystemClock.elapsedRealtime() - openedAt} ${usbState(usb, device)}")
        if (connection == null) {
            return
        }
        try {
            var version: Int? = null
            for (attempt in 1..PROTOCOL_ATTEMPTS) {
                if (!usb.deviceList.values.any { it.deviceId == device.deviceId } || !usb.hasPermission(device)) {
                    LinkLog.appendProbe(context, "AOA probe: GET_PROTOCOL aborted attempt=$attempt ${usbState(usb, device)}")
                    return
                }
                val bytes = ByteArray(2)
                val startedAt = SystemClock.elapsedRealtime()
                val result = try {
                    connection.controlTransfer(0xc0, 51, 0, 0, bytes, bytes.size, TIMEOUT_MS)
                } catch (error: RuntimeException) {
                    LinkLog.appendProbe(context,
                        "AOA probe: GET_PROTOCOL attempt=$attempt exception=${error.javaClass.simpleName} ${usbState(usb, device)}")
                    -1
                }
                val candidate = if (result == 2) {
                    (bytes[0].toInt() and 0xff) or ((bytes[1].toInt() and 0xff) shl 8)
                } else null
                LinkLog.appendProbe(context,
                    "AOA probe: GET_PROTOCOL attempt=$attempt result=$result version=${candidate ?: "unknown"} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} ${usbState(usb, device)}")
                if (candidate != null && candidate in 1..2) { version = candidate; break }
                if (attempt < PROTOCOL_ATTEMPTS) Thread.sleep(RETRY_PAUSE_MS)
            }
            if (version == null) {
                LinkLog.appendProbe(context, "AOA probe: GET_PROTOCOL exhausted; SDK will take over without SEND_STRING")
                return
            }

            // Match the SDK's public identity prefix exactly; change only its missing terminator.
            val fields = arrayOf("ICCOA", "CarLink", "ICCOA CarLink")
            for ((index, value) in fields.withIndex()) {
                val bytes = AoaIdentityEncoding.encode(value)
                var accepted = false
                for (attempt in 1..STRING_ATTEMPTS) {
                    if (!usb.deviceList.values.any { it.deviceId == device.deviceId } || !usb.hasPermission(device)) {
                        LinkLog.appendProbe(context, "AOA probe: SEND_STRING aborted index=$index ${usbState(usb, device)}")
                        return
                    }
                    val startedAt = SystemClock.elapsedRealtime()
                    val result = try {
                        connection.controlTransfer(0x40, 52, 0, index, bytes, bytes.size, TIMEOUT_MS)
                    } catch (error: RuntimeException) {
                        LinkLog.appendProbe(context,
                            "AOA probe: SEND_STRING index=$index attempt=$attempt exception=${error.javaClass.simpleName} ${usbState(usb, device)}")
                        -1
                    }
                    LinkLog.appendProbe(context,
                        "AOA probe: SEND_STRING index=$index attempt=$attempt result=$result expected=${bytes.size} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} ${usbState(usb, device)}")
                    if (result == bytes.size) { accepted = true; break }
                    if (attempt < STRING_ATTEMPTS) Thread.sleep(RETRY_PAUSE_MS)
                }
                if (!accepted) {
                    LinkLog.appendProbe(context, "AOA probe: stopped at index=$index; START_ACCESSORY not sent")
                    return
                }
            }
            LinkLog.appendProbe(context, "AOA probe: first 3 terminated strings accepted; START_ACCESSORY not sent")
        } finally {
            connection.close()
            LinkLog.appendProbe(context, "AOA probe: connection closed; SDK handoff ${usbState(usb, device)}")
        }
    }

    private fun usbState(usb: UsbManager, device: UsbDevice): String {
        val devices = runCatching { usb.deviceList.values.toList() }.getOrDefault(emptyList())
        return "present=${devices.any { it.deviceId == device.deviceId }} permission=${usb.hasPermission(device)} " +
            "bus=${devices.joinToString(",") { "${it.vendorId.toString(16)}:${it.productId.toString(16)}" }}"
    }
}

/** The AOA SEND_STRING payload contract, kept independent of Android for unit verification. */
internal object AoaIdentityEncoding {
    fun encode(value: String): ByteArray {
        val raw = value.toByteArray(Charsets.UTF_8)
        require(raw.size < 256 && !value.contains('\u0000'))
        return raw + 0.toByte()
    }
}
