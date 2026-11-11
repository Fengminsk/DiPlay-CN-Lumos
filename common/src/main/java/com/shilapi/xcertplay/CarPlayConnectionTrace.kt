package com.shilapi.xcertplay

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small, redacted timeline that survives the connection Service running before the video host. */
internal object CarPlayConnectionTrace {
    const val FILE_NAME = "carplay-connection.log"
    private const val MAX_BYTES = 256 * 1024
    private val lock = Any()

    fun append(context: Context, event: String) {
        val safe = DiagnosticRedactor.redact(event) ?: return
        synchronized(lock) {
            runCatching {
                val file = File(File(context.applicationContext.filesDir, "logs"), FILE_NAME)
                file.parentFile?.mkdirs()
                if (file.length() > MAX_BYTES) {
                    val recent = file.readLines().takeLast(1_000)
                    file.writeText(recent.joinToString("\n", postfix = if (recent.isEmpty()) "" else "\n"))
                }
                val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                file.appendText("$time $safe\n")
            }
        }
    }
}
