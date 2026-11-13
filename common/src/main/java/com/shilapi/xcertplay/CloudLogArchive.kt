package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.link.LinkLog
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Uploads the redacted session, ICCOA, and CarPlay connection logs in minute-sized slices.
 *
 * Only the bytes appended since the last successful upload are sent, so a failed upload retries the
 * same slice on the next minute instead of losing it. The offset advances only after COS answers 200,
 * which makes the local log file the retry queue: nothing is copied, compressed or repackaged.
 */
internal object CloudLogArchive {
    private const val TAG = "DiPlayCloudLog"
    private const val PREFS = "cloud_log_offsets"
    private const val KEY_OFFSET = "offset"
    private const val SOURCE = "diplay.log"
    internal val sources = listOf(
        SOURCE, "iccoa-link.log", LinkLog.AOA_PROBE_FILE_NAME, CarPlayConnectionTrace.FILE_NAME,
    )
    // One minute's slice is normally a few KB; the cap only bounds a long offline backlog per retry.
    private const val MAX_SEGMENT_BYTES = 256 * 1024
    private const val INTERVAL_SECONDS = 60L
    private const val CONTENT_TYPE = "text/plain"

    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diplay-cloud-logs").apply { isDaemon = true }
    }
    @Volatile private var context: Context? = null

    @Synchronized fun start(appContext: Context) {
        if (context != null) return
        val app = appContext.applicationContext
        context = app
        // Releases before this one queued gzip files; they belong to a retired format.
        runCatching {
            File(app.noBackupFilesDir, "cloud-log-outbox").listFiles()
                ?.filter { it.name.endsWith(".log.gz") }?.forEach { it.delete() }
        }
        // The first run after an upgrade starts at the current end instead of replaying history.
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val source = File(File(app.filesDir, "logs"), SOURCE)
        if (!prefs.contains(KEY_OFFSET) && source.isFile) {
            prefs.edit().putLong(KEY_OFFSET, source.length()).commit()
        }
        worker.scheduleWithFixedDelay({ flushSafely() }, 0, INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    fun configurationChanged() { if (context != null) worker.execute { flushSafely() } }

    private fun flushSafely() {
        val app = context ?: return
        try {
            val config = CosLogSettings.load(app)
            if (config == null || !config.enabled) {
                // Upload is off: move the offset forward so re-enabling never sends what was
                // written while the driver had switched it off.
                skipWhileDisabled(app)
                return
            }
            for (source in sources) {
                // A corrupt or temporarily busy file must not block the other connection logs.
                try { uploadNextSlice(app, config, source) } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            // Network and file errors must never expose credentials, signed requests, or log content.
        }
    }

    @Synchronized
    private fun skipWhileDisabled(app: Context) {
        val editor = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        for (name in sources) {
            val source = File(File(app.filesDir, "logs"), name)
            if (source.isFile) editor.putLong(offsetKey(name), source.length())
        }
        editor.commit()
    }

    @Synchronized
    private fun uploadNextSlice(app: Context, config: CosLogSettings, name: String) {
        val source = File(File(app.filesDir, "logs"), name)
        if (!source.isFile) return
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val offset = offsetKey(name)
        val length = source.length()
        // A reset or a rotation truncates the live file; the old bytes were already uploaded.
        val start = prefs.getLong(offset, 0L).let { if (it > length) 0L else it }
        if (length <= start) return
        val chunk = readCompleteLines(source, start) ?: return
        val payload = chunk.first
        val end = chunk.second
        val safe = payload.split('\n').dropLast(1).asSequence()
            .mapNotNull(DiagnosticRedactor::redact)
            .joinToString("\n")
            .let { if (it.isEmpty()) it else "$it\n" }
        if (safe.isBlank()) {
            advance(prefs, offset, end)
            return
        }
        val key = objectKey(app.packageName, name, System.currentTimeMillis(), start)
        CosLogClient(config).put(key, safe.toByteArray(Charsets.UTF_8), CONTENT_TYPE)
        advance(prefs, offset, end)
        Log.i(TAG, "uploaded ${safe.length} chars to $key")
    }

    private fun offsetKey(name: String): String = if (name == SOURCE) KEY_OFFSET else "offset:$name"

    private fun advance(prefs: android.content.SharedPreferences, key: String, end: Long) {
        prefs.edit().putLong(key, end).commit()
    }

    /** Reads from [start] up to the last newline in the read window, so a partial line stays queued. */
    internal fun readCompleteLines(source: File, start: Long): Pair<String, Long>? {
        RandomAccessFile(source, "r").use { file ->
            file.seek(start)
            val room = minOf(MAX_SEGMENT_BYTES.toLong(), file.length() - start).toInt()
            if (room <= 0) return null
            val buffer = ByteArray(room)
            val count = file.read(buffer)
            if (count <= 0) return null
            var newline = -1
            for (index in count - 1 downTo 0) {
                if (buffer[index] == '\n'.code.toByte()) {
                    newline = index
                    break
                }
            }
            if (newline < 0) return null
            return String(buffer, 0, newline + 1, Charsets.UTF_8) to start + newline + 1
        }
    }

    internal fun objectKey(packageName: String, source: String, now: Long, start: Long): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val clock = SimpleDateFormat("HH-mm-ss", Locale.US).format(Date(now))
        return "Logs/$packageName/$day/$clock-$start-$source"
    }
}
