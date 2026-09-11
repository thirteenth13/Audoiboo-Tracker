package org.audoiboo.tracker.plugin

import android.webkit.CookieManager
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

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
        val userAgent = browserUserAgent ?: DEFAULT_USER_AGENT

        fun execute(recoveryAttempt: Boolean): Connection.Response {
            val connection = Jsoup.connect(request.url)
                .userAgent(userAgent)
                .header("Accept-Language", "ru-RU,ru;q=0.9,uk;q=0.8,en;q=0.6")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .timeout(TIMEOUT_MS)
                .maxBodySize(maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .followRedirects(false)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .headers(request.headers)
                .method(Connection.Method.GET)

            if (recoveryAttempt) {
                // A few providers occasionally close a reused/gzip connection before Jsoup has
                // consumed the full response. Retry once with a fresh, non-compressed connection.
                // The URL is unchanged, so sandbox redirect/host validation remains intact.
                if (request.headers.keys.none { it.equals("Connection", ignoreCase = true) }) {
                    connection.header("Connection", "close")
                }
                if (request.headers.keys.none { it.equals("Accept-Encoding", ignoreCase = true) }) {
                    connection.header("Accept-Encoding", "identity")
                }
            }

            if (!origin.isNullOrBlank()) connection.referrer(origin)
            if (!cookies.isNullOrBlank() && request.headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                connection.header("Cookie", cookies)
            }
            return connection.execute()
        }

        val response = try {
            try {
                execute(recoveryAttempt = false)
            } catch (t: Throwable) {
                if (!shouldRetryTruncatedResponse(t)) throw t
                val host = runCatching { URI(request.url).host }.getOrNull().orEmpty()
                SeriesDiagnosticLog.w("NET RETRY truncated-response host=$host url=${request.url}")
                execute(recoveryAttempt = true)
            }
        } catch (t: Throwable) {
            if (!shouldRetryWithDoh(t)) throw t
            val host = runCatching { URI(request.url).host }.getOrNull().orEmpty()
            SeriesDiagnosticLog.w("NET RETRY doh host=$host reason=${networkFailureName(t)} url=${request.url}")
            val fallback = DohHttpFallback.get(
                request = request,
                maxResponseBytes = maxResponseBytes,
                userAgent = userAgent,
                origin = origin,
                cookies = cookies
            )
            storeCookies(request.url, fallback.headers)
            SeriesDiagnosticLog.i("NET DOH GET ${request.url} -> ${fallback.statusCode}, ${fallback.body.length}b")
            return fallback
        }

        val headers = response.multiHeaders().mapValues { (_, values) -> values.toList() }
        storeCookies(request.url, headers)
        return PluginHttpResponse(
            statusCode = response.statusCode(),
            finalUrl = response.url().toString(),
            body = response.body(),
            headers = headers
        )
    }

    private fun storeCookies(url: String, headers: Map<String, List<String>>) {
        runCatching {
            headers.entries
                .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
                ?.value
                .orEmpty()
                .forEach { value -> CookieManager.getInstance().setCookie(url, value) }
            CookieManager.getInstance().flush()
        }
    }

    internal fun shouldRetryTruncatedResponse(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is EOFException }

    internal fun shouldRetryWithDoh(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is UnknownHostException ||
                it is ConnectException ||
                it is NoRouteToHostException ||
                it is SocketTimeoutException
        }

    internal fun networkFailureName(error: Throwable): String =
        generateSequence(error) { it.cause }
            .firstOrNull {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is NoRouteToHostException ||
                    it is SocketTimeoutException
            }
            ?.javaClass
            ?.simpleName
            ?: error.javaClass.simpleName
}
