package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Imports the COS configuration from a JSON file on a USB drive.
 *
 * The driver picks the drive's folder once through the system picker and the grant is persisted, so
 * plugging the drive in again needs no interaction. The file never switches upload on: like the
 * JSON picked by hand it keeps the current switch value, and a file that would change nothing is
 * ignored. Only app-private settings change; the drive is read, never written.
 */
internal object CosUsbConfig {
    /** The name the settings screen documents; any other root-level `.json` still works. */
    const val FILE_NAME = "diplay-cos.json"
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_CANDIDATES = 4
    private const val TAG = "DiPlayCosUsb"
    private const val PREFS = "cos_usb_config"
    private const val KEY_TREE = "tree_uri"
    private const val MOUNT_SETTLE_MS = 400L
    private const val ATTACH_SETTLE_MS = 2_500L

    enum class Result { IMPORTED, UNCHANGED, MISSING, NO_FOLDER }

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-cos-usb").apply { isDaemon = true }
    }
    // Lazy so the pure candidate helpers stay usable in plain JVM tests.
    private val main by lazy { Handler(Looper.getMainLooper()) }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedFolder(context: Context): Uri? =
        prefs(context).getString(KEY_TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }

    fun saveFolder(context: Context, folder: Uri) {
        // Without the persisted grant the folder is unreadable after the next reboot.
        runCatching {
            context.contentResolver.takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w(TAG, "folder grant was not persisted") }
        prefs(context).edit().putString(KEY_TREE, folder.toString()).commit()
    }

    /** Reads the saved folder off the main thread and reports what the file did. */
    fun refresh(context: Context, onResult: (Result) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = runCatching { import(app) }.getOrElse {
                // An unplugged drive makes the provider query fail; that is not an error to report.
                Log.i(TAG, "import skipped: ${it.javaClass.simpleName}")
                Result.MISSING
            }
            main.post { onResult(result) }
        }
    }

    /** Watches for the drive appearing while this process lives; the caller closes the watch. */
    fun watch(context: Context, onImported: () -> Unit): Closeable {
        val app = context.applicationContext
        val scan = Runnable { refresh(app) { if (it == Result.IMPORTED) onImported() } }
        val mountReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                main.removeCallbacks(scan)
                main.postDelayed(scan, MOUNT_SETTLE_MS)
            }
        }
        val attachReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // vold mounts the volume after this broadcast, so wait for it instead of racing it.
                main.removeCallbacks(scan)
                main.postDelayed(scan, ATTACH_SETTLE_MS)
            }
        }
        val mountFilter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED).apply { addDataScheme("file") }
        val attachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        ContextCompat.registerReceiver(app, mountReceiver, mountFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(app, attachReceiver, attachFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return Closeable {
            main.removeCallbacks(scan)
            runCatching { app.unregisterReceiver(mountReceiver) }
            runCatching { app.unregisterReceiver(attachReceiver) }
        }
    }

    @Synchronized
    private fun import(context: Context): Result {
        val folder = savedFolder(context) ?: return Result.NO_FOLDER
        val current = CosLogSettings.load(context)
        val parsed = pick(readDocuments(context, folder), current?.enabled == true) ?: return Result.MISSING
        // A drive left plugged in must not undo a credential the driver edited on the head unit.
        if (current == parsed) return Result.UNCHANGED
        CosLogSettings.save(context, parsed)
        CloudLogArchive.configurationChanged()
        Log.i(TAG, "COS settings imported from USB")
        return Result.IMPORTED
    }

    /** `diplay-cos.json` first, then any other JSON, so a stray file cannot win over the named one. */
    internal fun preferredJson(names: List<String>): List<String> =
        names.filter { it.endsWith(".json", ignoreCase = true) }
            .sortedWith(compareBy({ !it.equals(FILE_NAME, ignoreCase = true) }, { it.lowercase() }))

    /** First document that parses as a COS configuration; an invalid file is skipped, never fatal. */
    internal fun pick(documents: List<Pair<String, String>>, enabled: Boolean): CosLogSettings? =
        documents.firstNotNullOfOrNull { (_, text) ->
            runCatching { CosLogSettings.fromJson(text, enabled) }.getOrNull()
        }

    private data class DocumentRef(val id: String, val name: String, val size: Long)

    private fun readDocuments(context: Context, folder: Uri): List<Pair<String, String>> {
        val documents = children(context, folder).orEmpty()
        val result = mutableListOf<Pair<String, String>>()
        for (name in preferredJson(documents.map { it.name }).take(MAX_CANDIDATES)) {
            val document = documents.firstOrNull { it.name == name && it.size in 0..MAX_BYTES.toLong() } ?: continue
            result += name to (readDocument(context, folder, document.id) ?: continue)
        }
        return result
    }

    private fun children(context: Context, folder: Uri): List<DocumentRef>? = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            folder, DocumentsContract.getTreeDocumentId(folder),
        )
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE)
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            val found = mutableListOf<DocumentRef>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                found += DocumentRef(id, cursor.getString(1).orEmpty(),
                    if (cursor.isNull(2)) 0L else cursor.getLong(2))
            }
            found
        }
    }.getOrNull()

    private fun readDocument(context: Context, folder: Uri, id: String): String? = runCatching {
        val uri = DocumentsContract.buildDocumentUriUsingTree(folder, id)
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(MAX_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            if (count > MAX_BYTES) null else buffer.copyOf(count)
        } ?: return@runCatching null
        // Car head units' editors still write a BOM often enough to break the first key.
        String(bytes, Charsets.UTF_8).trim().removePrefix("\uFEFF").takeIf { it.isNotEmpty() }
    }.getOrNull()
}
