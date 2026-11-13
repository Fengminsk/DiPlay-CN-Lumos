package com.shilapi.xcertplay.link

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device record of the wired ICCOA path.
 *
 * The Pad is normally not attached to a PC while a phone is plugged in, so logcat alone loses the
 * evidence. This keeps a rolling file that the diagnostic report picks up, and captures the SDK's
 * own AOA lines (which never reach our code) by reading our own UID's logcat.
 *
 * The SDK's tag is not what the class names suggest: `EasyLog` starts with the `easylog_` prefix,
 * but `UCarAdapter`'s static initialiser calls `EasyLog.setPrefix("UCar_")`, so every SDK line
 * reads `UCar_AOAHostSetup`, `UCar_ShareLinkService`, … . Matching is therefore by prefix and by
 * tag fragment, never by exact tag.
 */
object LinkLog {

    private const val TAG = "DiPlay/LinkLog"
    private const val MAX_BYTES = 256 * 1024
    private const val FILE_NAME = "iccoa-link.log"
    const val AOA_PROBE_FILE_NAME = "iccoa-aoa-probe.log"

    /** Any line whose tag contains one of these belongs to the ICCOA stack. */
    private val sdkMarkers = listOf(
        "UCar_", "easylog_", "UCarProtocol", "AOAHostSetup", "AOAConnectManager",
        "AOAAccessoryReceiver", "ShareLinkService", "BluetoothLeService",
        "WifiApService", "WifiP2pService", "MiAuto",
    )

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    @Volatile
    private var capture: Thread? = null

    fun append(context: Context, line: String) {
        Log.i(TAG, line)
        synchronized(lock) {
            try {
                val file = file(context)
                file.parentFile?.mkdirs()
                if (file.length() > MAX_BYTES) {
                    val kept = file.readLines().takeLast(600)
                    file.writeText(kept.joinToString("\n", postfix = "\n"))
                }
                file.appendText("${stamp.format(Date())} $line\n")
            } catch (t: Throwable) {
                Log.w(TAG, "could not write the link log", t)
            }
        }
    }

    fun report(context: Context): String = synchronized(lock) {
        val file = file(context)
        if (!file.exists() || file.length() == 0L) "no ICCOA link activity recorded" else file.readText()
    }

    /** A separate low-volume record survives the SDK's noisy AOA retry loop until COS uploads it. */
    fun appendProbe(context: Context, line: String) {
        append(context, line)
        synchronized(lock) {
            try {
                val probeFile = File(File(context.filesDir, "logs"), AOA_PROBE_FILE_NAME)
                probeFile.parentFile?.mkdirs()
                if (probeFile.length() > 128 * 1024) {
                    val kept = probeFile.readLines().takeLast(200)
                    probeFile.writeText(kept.joinToString("\n", postfix = "\n"))
                }
                probeFile.appendText("${stamp.format(Date())} $line\n")
            } catch (t: Throwable) {
                Log.w(TAG, "could not write the AOA probe log", t)
            }
        }
    }

    /** Starts reading the SDK's logcat lines once; repeated calls do nothing. */
    fun startCapture(context: Context) {
        if (capture != null) return
        synchronized(this) {
            if (capture != null) return
            val app = context.applicationContext
            capture = Thread({
                try {
                    val process = ProcessBuilder("logcat", "-v", "threadtime")
                        .redirectErrorStream(true)
                        .start()
                    append(app, "SDK log capture started")
                    val aoaCounts = mutableMapOf<String, Int>()
                    process.inputStream.bufferedReader().forEachLine { raw ->
                        // append() also writes to logcat. Recapturing that tag recursively grows
                        // the line on every pass and evicts the useful handshake from the file.
                        if (raw.contains(TAG) || raw.contains("bulkTransfer")) return@forEachLine
                        if (raw.isNotBlank() && sdkMarkers.any { raw.contains(it) }) {
                            append(app, raw.trimEnd())
                            val stage = when {
                                raw.contains("usb device AOA version is") -> "protocol-version"
                                raw.contains("getProtocolVersion Fail") -> "get-protocol-failed"
                                raw.contains("send identity AOA_DESCRIPTION fail") -> "description-failed"
                                raw.contains("sendIdentityStrings Fail") -> "identity-failed"
                                raw.contains("USB Device Detached") -> "usb-detached"
                                raw.contains("USB Device Attached") -> "usb-attached"
                                raw.contains("onInitSuccess()") -> "sdk-initialized"
                                else -> null
                            }
                            if (stage != null) {
                                val count = (aoaCounts[stage] ?: 0) + 1
                                aoaCounts[stage] = count
                                // Preserve the handshake result even if a noisy SDK retry loop rotates the full log.
                                if (count == 1 || count and (count - 1) == 0) {
                                    appendProbe(app, "AOA SDK stage=$stage occurrences=$count")
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    append(app, "SDK log capture failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }, "IccoaLinkLog").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }.also { it.start() }
        }
    }

    private fun file(context: Context) = File(File(context.filesDir, "logs"), FILE_NAME)
}
