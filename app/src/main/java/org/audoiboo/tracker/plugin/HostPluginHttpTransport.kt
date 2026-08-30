package org.audoiboo.tracker.plugin

import org.jsoup.Connection
import org.jsoup.Jsoup

/**
 * Production transport used by the plugin sandbox. It performs exactly one HTTP hop and never
 * follows redirects itself; PluginSandboxSession validates every redirect target before another
 * request is made. This keeps undeclared hosts unreachable even through server redirects.
 */
object HostPluginHttpTransport : PluginHttpTransport {
    private const val USER_AGENT = "Mozilla/5.0 (Android) AudoibooTracker/PluginHost"
    private const val TIMEOUT_MS = 20_000

    override fun get(request: PluginHttpRequest, maxResponseBytes: Long): PluginHttpResponse {
        val response = Jsoup.connect(request.url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .maxBodySize(maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .followRedirects(false)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .headers(request.headers)
            .method(Connection.Method.GET)
            .execute()

        val headers = response.headers().mapValues { (_, value) -> listOf(value) }
        return PluginHttpResponse(
            statusCode = response.statusCode(),
            finalUrl = response.url().toString(),
            body = response.body(),
            headers = headers
        )
    }
}
