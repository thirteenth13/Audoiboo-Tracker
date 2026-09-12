package org.audoiboo.tracker.plugin.flibusta

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

fun interface FlibustaTransport {
    fun get(url: String, headers: Map<String, String>): FlibustaHttpResponse
}

class HttpUrlConnectionFlibustaTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 45_000,
    private val maxBodyBytes: Int = 64 * 1024 * 1024
) : FlibustaTransport {
    override fun get(url: String, headers: Map<String, String>): FlibustaHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            useCaches = false
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val status = connection.responseCode
            val input = if (status >= 400) connection.errorStream else connection.inputStream
            val body = if (input == null) ByteArray(0) else input.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= maxBodyBytes) { "Flibusta response exceeds $maxBodyBytes bytes" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { it.key!! }
                .mapValues { it.value.orEmpty() }
            return FlibustaHttpResponse(status, connection.url.toString(), responseHeaders, body)
        } finally {
            connection.disconnect()
        }
    }
}
