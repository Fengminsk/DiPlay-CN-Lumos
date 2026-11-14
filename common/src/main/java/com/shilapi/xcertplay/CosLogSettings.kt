package com.shilapi.xcertplay

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** COS credentials are entered on the device and live only in private, excluded-from-backup storage. */
internal data class CosLogSettings(
    val bucket: String,
    val endpoint: String,
    val secretId: String,
    val secretKey: String,
    val enabled: Boolean,
) {
    fun valid(): Boolean = bucket.matches(Regex("[a-z0-9][a-z0-9-]{0,60}-[0-9]+")) &&
        endpoint.matches(Regex("cos\\.[a-z0-9-]+\\.myqcloud\\.com")) &&
        secretId.isNotBlank() && secretKey.isNotBlank() &&
        !secretId.contains('\n') && !secretKey.contains('\n')

    /** True when both configurations address the same bucket with the same key. */
    fun sameCredentialsAs(other: CosLogSettings): Boolean =
        bucket == other.bucket && endpoint == other.endpoint &&
            secretId == other.secretId && secretKey == other.secretKey

    companion object {
        fun fromJson(text: String, enabled: Boolean = false): CosLogSettings {
            val json = JSONObject(text)
            fun field(camel: String, snake: String): String =
                json.optString(camel).ifBlank { json.optString(snake) }.trim()
            return CosLogSettings(field("bucket", "bucket"), field("endpoint", "endpoint")
                .removePrefix("https://").trimEnd('/'), field("secretId", "secret_id"),
                field("secretKey", "secret_key"), enabled).also {
                require(it.valid()) { "Invalid COS configuration" }
            }
        }

        fun load(context: Context): CosLogSettings? {
            val saved = saved(context)
            val baked = bakedRead(context)
            if (saved == null) {
                baked?.let { save(context, it) }
                return baked
            }
            // A baked private configuration is an operator-provided analysis build. A release before
            // this one persisted the same credentials as disabled, which would silence uploads; treat
            // the identical credentials as enabled once. A different, driver-entered configuration and
            // any later change keep their own value.
            if (baked != null && !saved.enabled && saved.sameCredentialsAs(baked)) {
                val migrated = saved.copy(enabled = true)
                save(context, migrated)
                return migrated
            }
            return saved
        }

        // A private build can bake one configuration into the APK. It seeds the on-device setting
        // once; afterwards the driver's own value wins, including switching upload off.
        private fun bakedRead(context: Context): CosLogSettings? = runCatching {
            val text = context.assets.open("cos-log-default.json").bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            fromJson(text, JSONObject(text).optBoolean("enabled", true))
        }.getOrNull()

        private fun saved(context: Context): CosLogSettings? = runCatching {
            val file = AtomicFile(File(context.noBackupFilesDir, "cos-log-settings.json"))
            if (!file.baseFile.exists()) null
            else {
                val json = JSONObject(String(file.openRead().use { it.readBytes() }, Charsets.UTF_8))
                fromJson(json.toString(), json.optBoolean("enabled", false))
            }
        }.getOrNull()

        fun save(context: Context, value: CosLogSettings) {
            require(value.valid())
            val file = AtomicFile(File(context.noBackupFilesDir, "cos-log-settings.json"))
            val json = JSONObject().put("bucket", value.bucket).put("endpoint", value.endpoint)
                .put("secretId", value.secretId).put("secretKey", value.secretKey)
                .put("enabled", value.enabled).toString().toByteArray(Charsets.UTF_8)
            val stream = file.startWrite()
            try { stream.write(json); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        }
    }
}
