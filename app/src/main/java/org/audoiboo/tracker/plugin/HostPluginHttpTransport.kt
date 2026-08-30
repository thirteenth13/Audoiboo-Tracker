package org.audoiboo.tracker.plugin

import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URI

/**
 * Production transport used by the plugin sandbox. It performs exactly one HTTP hop and never
 * follows redirects itself; PluginSandboxSession validates every redirect target before another
 * request is made. This keeps undeclared hosts unreachable even through server redirects.
 */
object HostPluginHttpTransport : PluginHttpTransport {
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 20_000

    override fun get(request: PluginHttpRequest, maxResponseBytes: Long): PluginHttpResponse {
        val origin = runCatching {
            val uri = URI(request.url)
            "${uri.scheme}://${uri.host}/"
        }.getOrNull()

        val connection = Jsoup.connect(request.url)
            .userAgent(USER_AGENT)
            .header("Accept-Language", "ru-RU,ru;q=0.9,uk;q=0.8,en;q=0.6")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .timeout(TIMEOUT_MS)
            .maxBodySize(maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .followRedirects(false)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .headers(request.headers)
            .method(Connection.Method.GET)

        if (!origin.isNullOrBlank()) connection.referrer(origin)
        val response = connection.execute()

        val headers = response.headers().mapValues { (_, value) -> listOf(value) }
        return PluginHttpResponse(
            statusCode = response.statusCode(),
            finalUrl = response.url().toString(),
            body = response.body(),
            headers = headers
        )
    }
}
