package com.shilapi.xcertplay

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Best-effort upload of redacted app logs. Only an explicit on-device opt-in permits network traffic. */
internal object CloudLogArchive {
    private const val MAX_OUTBOX_BYTES = 128L * 1024 * 1024
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-cloud-logs").apply { isDaemon = true }
    }
    @Volatile private var context: Context? = null

    @Synchronized fun start(appContext: Context) {
        if (context != null) return
        context = appContext.applicationContext
        worker.scheduleWithFixedDelay({ flushSafely() }, 0, 30, TimeUnit.SECONDS)
    }

    fun configurationChanged() { if (context != null) worker.execute { flushSafely() } }

    private fun flushSafely() {
        val app = context ?: return
        val config = CosLogSettings.load(app) ?: return
        if (!config.enabled) return
        try {
            val outbox = File(app.noBackupFilesDir, "cloud-log-outbox").apply { mkdirs() }
            enqueueChangedLogs(app, outbox)
            val client = CosLogClient(config)
            for (file in outbox.listFiles().orEmpty().filter { it.name.endsWith(".log.gz") }.sortedBy { it.name }) {
                val pieces = file.name.split("--", limit = 2)
                if (pieces.size != 2 || !pieces[0].matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) continue
                val key = "Logs/${app.packageName}/${pieces[0]}/${pieces[1]}"
                client.put(key, file.readBytes())
                file.delete() // A successful HTTP 200 is the only point at which a queued copy is discarded.
            }
        } catch (_: Exception) {
            // Network and file errors must never expose credentials, signed requests, or log content.
        }
    }

    private fun enqueueChangedLogs(app: Context, outbox: File) {
        val preferences = app.getSharedPreferences("cloud_log_snapshots", Context.MODE_PRIVATE)
        val sourceDir = File(app.filesDir, "logs")
        for (name in SessionLogFile.REPORT_NAMES) {
            val source = File(sourceDir, name)
            if (!source.isFile || source.length() == 0L || source.length() > SessionLogFile.MAX_BYTES + 4096) continue
            val safe = source.readLines().mapNotNull(DiagnosticRedactor::redact).joinToString("\n", postfix = "\n")
            if (safe.isBlank()) continue
            val digest = MessageDigest.getInstance("SHA-256").digest(safe.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }
            if (preferences.getString(name, null) == digest) continue
            if (outbox.listFiles().orEmpty().sumOf(File::length) >= MAX_OUTBOX_BYTES) return
            val gzipped = ByteArrayOutputStream().also { buffer ->
                GZIPOutputStream(buffer).use { it.write(safe.toByteArray(Charsets.UTF_8)) }
            }.toByteArray()
            if (outbox.listFiles().orEmpty().sumOf(File::length) + gzipped.size > MAX_OUTBOX_BYTES) return
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val timestamp = System.currentTimeMillis()
            val fileName = "$day--$timestamp-${UUID.randomUUID()}-$name.gz"
            val target = File(outbox, fileName)
            val temp = File(outbox, "$fileName.tmp")
            FileOutputStream(temp).use { stream -> stream.write(gzipped); stream.fd.sync() }
            if (!temp.renameTo(target)) { temp.delete(); return }
            preferences.edit().putString(name, digest).commit()
        }
    }
}
