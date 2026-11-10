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
                    process.inputStream.bufferedReader().forEachLine { raw ->
                        if (raw.isNotBlank() && sdkMarkers.any { raw.contains(it) }) {
                            append(app, raw.trimEnd())
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
