package com.shilapi.xcertplay

import android.util.Base64
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

/** Minimal COS XML API PUT client, adapted from CarsBridge's CosUploadClient. */
internal class CosLogClient(private val settings: CosLogSettings) {
    fun put(key: String, body: ByteArray) {
        require(settings.valid())
        require(key.matches(Regex("Logs/[A-Za-z0-9_.]+/[A-Za-z0-9_./-]+\\.log\\.gz")))
        val host = "${settings.bucket}.${settings.endpoint}"
        val path = "/$key"
        val md5 = Base64.encodeToString(MessageDigest.getInstance("MD5").digest(body), Base64.NO_WRAP)
        val headers = sortedMapOf("content-md5" to md5, "content-type" to "application/gzip", "host" to host)
        val now = System.currentTimeMillis() / 1000
        val period = "${now - 60};${now + 900}"
        val authorization = authorize(settings.secretId, settings.secretKey, path, headers, period)
        val connection = URL("https://$host$path").openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "PUT"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Content-Type", "application/gzip")
            connection.setRequestProperty("Content-MD5", md5)
            connection.setRequestProperty("Authorization", authorization)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) throw java.io.IOException("COS HTTP ${connection.responseCode}")
        } finally { connection.disconnect() }
    }

    companion object {
        internal fun authorize(id: String, secret: String, path: String,
                               headers: Map<String, String>, period: String): String {
            val encoded = headers.toSortedMap().map { (name, value) -> "${encode(name)}=${encode(value)}" }.joinToString("&")
            val http = "put\n$path\n\n$encoded\n"
            val signKey = hmac(secret, period)
            val toSign = "sha1\n$period\n${sha1(http)}\n"
            return "q-sign-algorithm=sha1&q-ak=${encode(id)}&q-sign-time=$period&q-key-time=$period" +
                "&q-header-list=${headers.keys.sorted().joinToString(";")}&q-url-param-list=&q-signature=${hmac(signKey, toSign)}"
        }

        private fun sha1(text: String) = hex(MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)))
        private fun hmac(key: String, text: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return hex(mac.doFinal(text.toByteArray(Charsets.UTF_8)))
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
    }
}
