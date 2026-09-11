package org.audoiboo.tracker.plugin

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal object ProxyHttpFallback {
    fun get(
        request: PluginHttpRequest,
        maxResponseBytes: Long,
        userAgent: String,
        origin: String?,
        cookies: String?,
        config: NetworkProxyConfig,
        timeoutMs: Int
    ): PluginHttpResponse {
        require(config.isUsable) { "Proxy fallback is not configured" }

        val proxyType = when (config.type) {
            NetworkProxyType.HTTP -> Proxy.Type.HTTP
            NetworkProxyType.SOCKS5 -> Proxy.Type.SOCKS
        }
        val proxy = Proxy(proxyType, InetSocketAddress.createUnresolved(config.host, config.port))
        val builder = OkHttpClient.Builder()
            .proxy(proxy)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeoutMs.coerceAtMost(8_000).toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)

        if (config.type == NetworkProxyType.HTTP && config.username.isNotBlank()) {
            builder.proxyAuthenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.request.header("Proxy-Authorization") != null) return null
                    return response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(config.username, config.password))
                        .build()
                }
            })
        }

        val http = builder.build()
        val requestBuilder = Request.Builder().url(request.url).get()
            .header("User-Agent", userAgent)
            .header("Accept-Language", "ru-RU,ru;q=0.9,uk;q=0.8,en;q=0.6")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        request.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        if (!origin.isNullOrBlank() && request.headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            requestBuilder.header("Referer", origin)
        }
        if (!cookies.isNullOrBlank() && request.headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            requestBuilder.header("Cookie", cookies)
        }

        http.newCall(requestBuilder.build()).execute().use { response ->
            val bytes = response.body?.byteStream()?.use { input -> readLimited(input, maxResponseBytes) } ?: ByteArray(0)
            return PluginHttpResponse(
                statusCode = response.code,
                finalUrl = response.request.url.toString(),
                body = bytes.toString(StandardCharsets.UTF_8),
                headers = response.headers.names().associateWith { name -> response.headers.values(name) }
            )
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val safeLimit = limit.coerceAtLeast(0L)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            val remaining = safeLimit - total
            if (remaining <= 0) break
            val accepted = minOf(read.toLong(), remaining).toInt()
            out.write(buffer, 0, accepted)
            total += accepted
            if (accepted < read) break
        }
        return out.toByteArray()
    }
}
