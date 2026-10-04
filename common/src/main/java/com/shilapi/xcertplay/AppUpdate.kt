package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updates from this repository's GitHub releases: check the latest release, download its
 * APK into the cache, then hand it to the system package installer. The APK is signed with the
 * project's stable key, so the installer updates the installed app in place and keeps settings.
 */
object AppUpdate {
    private const val REPO_PATH = "serein-morii/DiPlay-CN"
    private const val GITHUB_LATEST = "https://api.github.com/repos/$REPO_PATH/releases/latest"
    private const val GITEE_LATEST = "https://gitee.com/api/v5/repos/oneeyear/DiPlay-CN/releases/latest"
    private const val ACCEPT = "application/vnd.github+json"
    private const val USER_AGENT = "DiPlay-CN-Updater"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 20_000
    private const val MAX_APK_BYTES = 200L * 1024 * 1024

    data class Release(
        /** The release tag, e.g. v0.2.10-cn.8. Compared without a leading v. */
        val tag: String,
        val apkUrl: String,
        val apkBytes: Long,
        val notesUrl: String,
        val notes: String,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-update").apply { isDaemon = true }
    }

    private const val CHANNEL_GITHUB = 0
    private const val CHANNEL_GITEE = 1
    private const val CHANNEL_MIRROR1 = 2
    private const val CHANNEL_MIRROR2 = 3

    /** GitHub-relaying proxies that also front api.github.com. */
    private val mirrorPrefixes = mapOf(
        CHANNEL_MIRROR1 to "https://gh-proxy.com/",
        CHANNEL_MIRROR2 to "https://ghproxy.net/",
    )

    @Volatile private var channel = CHANNEL_GITEE

    /** The settings page stores the chosen channel; the updater picks it up before each call. */
    fun setChannel(choice: Int) {
        channel = choice.coerceIn(CHANNEL_GITHUB, CHANNEL_MIRROR2)
    }

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    fun isAvailable(latest: Release, context: Context): Boolean =
        normalizeTag(latest.tag) != normalizeTag(currentVersion(context))

    internal fun normalizeTag(value: String): String =
        value.trim().removePrefix("v").removePrefix("V")

    /** Strip markdown so the in-app dialog stays readable on the head unit. */
    fun plainNotes(markdown: String, limit: Int = 1800): String {
        val text = markdown
            .replace(Regex("""\[([^\]]+)]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_`#>-]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return if (text.length <= limit) text else text.take(limit).trimEnd() + "…"
    }

    /** Fetches the latest full release (drafts and prereleases are excluded by GitHub). */
    fun check(onResult: (Release?, String?) -> Unit) {
        worker.execute {
            var result: Release? = null
            var failure: String? = null
            runCatching {
                val mirrorPrefix = mirrorPrefixes[channel]
                val latest = when {
                    channel == CHANNEL_GITEE -> GITEE_LATEST
                    mirrorPrefix != null -> mirrorPrefix + GITHUB_LATEST
                    else -> GITHUB_LATEST
                }
                val connection = URL(latest).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.setRequestProperty("Accept", ACCEPT)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.inputStream.use { stream ->
                    stream.readBytes().decodeToString()
                }.let { body ->
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name")
                    val asset = json.optJSONArray("assets")?.let { assets ->
                        (0 until assets.length()).asSequence()
                            .map { assets.optJSONObject(it) }
                            .firstOrNull { it?.optString("name", "")?.endsWith(".apk") == true }
                    }
                    if (tag.isNotEmpty() && asset != null) {
                        result = Release(
                            tag = tag,
                            apkUrl = asset.optString("browser_download_url"),
                            apkBytes = asset.optLong("size", 0L),
                            notesUrl = json.optString("html_url"),
                            notes = json.optString("body").orEmpty(),
                        )
                    }
                }
            }.onFailure { failure = it.message ?: it.javaClass.simpleName }
            val deliver = result
            val reason = failure
            main.post { onResult(deliver, reason) }
        }
    }

    /**
     * Downloads the APK into the cache dir. Reports progress as 0..100 (-1 before the size is
     * known) and the finished file. Calls [onError] with a short message instead of throwing.
     */
    fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit,
        onDone: (File) -> Unit,
        onError: (String) -> Unit,
    ) {
        worker.execute {
            runCatching {
                val directory = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(directory, "diplay-cn-${release.tag}.apk")
                val downloadUrl = mirrorPrefixes[channel]?.let { it + release.apkUrl } ?: release.apkUrl
                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = 60_000
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            check(copied <= MAX_APK_BYTES) { "download too large" }
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt()
                                main.post { onProgress(percent) }
                            }
                        }
                        if (total <= 0) main.post { onProgress(-1) }
                    }
                }
                main.post { onDone(target) }
            }.onFailure { failure ->
                val message = failure.message ?: failure.javaClass.simpleName
                main.post { onError(message) }
            }
        }
    }

    /** Opens the system installer for a downloaded APK; false when no installer accepted it. */
    fun install(context: Context, apk: File): Boolean {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.update", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
