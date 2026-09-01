package org.audoiboo.tracker.plugin

import android.webkit.CookieManager
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.net.URI

/**
 * Production transport used by the plugin sandbox. It performs exactly one HTTP hop and never
 * follows redirects itself; PluginSandboxSession validates every redirect target before another
 * request is made. This keeps undeclared hosts unreachable even through server redirects.
 *
 * When the in-app source browser has already opened a site, reuse its WebView user-agent and
 * cookies. Some sources return a browser-renderable page only after a browser session has been
 * established; without this bridge the visible WebView and the sandbox parser would effectively
 * be two unrelated clients.
 */
object HostPluginHttpTransport : PluginHttpTransport {
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    private const val TIMEOUT_MS = 20_000

    @Volatile
    private var browserUserAgent: String? = null

    fun updateBrowserUserAgent(value: String?) {
        browserUserAgent = value?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun get(request: PluginHttpRequest, maxResponseBytes: Long): PluginHttpResponse {
        val origin = runCatching {
            val uri = URI(request.url)
            "${uri.scheme}://${uri.host}/"
        }.getOrNull()
        val cookies = runCatching { CookieManager.getInstance().getCookie(request.url) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val connection = Jsoup.connect(request.url)
            .userAgent(browserUserAgent ?: DEFAULT_USER_AGENT)
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
        if (!cookies.isNullOrBlank() && request.headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            connection.header("Cookie", cookies)
        }

        val response = connection.execute()
        runCatching {
            response.multiHeaders()["Set-Cookie"].orEmpty().forEach { value ->
                CookieManager.getInstance().setCookie(request.url, value)
            }
            CookieManager.getInstance().flush()
        }

        val headers = response.headers().mapValues { (_, value) -> listOf(value) }
        return PluginHttpResponse(
            statusCode = response.statusCode(),
            finalUrl = response.url().toString(),
            body = response.body(),
            headers = headers
        )
    }
}
