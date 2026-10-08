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

        fun load(context: Context): CosLogSettings? = runCatching {
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
