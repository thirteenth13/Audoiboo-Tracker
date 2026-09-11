package org.audoiboo.tracker.plugin

import android.webkit.CookieManager
import org.jsoup.Connection
import org.jsoup.Jsoup
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException

/**
 * Production transport used by the plugin sandbox. It performs exactly one HTTP hop and never
 * follows redirects itself; PluginSandboxSession validates every redirect target before another
 * request is made. This keeps undeclared hosts unreachable even through server redirects.
 *
 * Network recovery order is deliberately conservative: system route first, DoH only for actual
 * name-resolution failures, then an explicitly configured proxy for DNS/IP/route blocks. TLS is
 * never downgraded and target URLs keep their original host names.
 */
object HostPluginHttpTransport : PluginHttpTransport {
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"
    private const val DEFAULT_TIMEOUT_MS = 20_000
    private const val FANTLAB_TIMEOUT_MS = 6_000

    @Volatile
    private var browserUserAgent: String? = null

    fun updateBrowserUserAgent(value: String?) {
        browserUserAgent = value?.trim()?.takeIf { it.isNotBlank() }
    }

    override fun get(request: PluginHttpRequest, maxResponseBytes: Long): PluginHttpResponse {
        val requestHost = runCatching { URI(request.url).host }.getOrNull().orEmpty()
        val timeoutMs = requestTimeoutMs(request.url)
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
                .timeout(timeoutMs)
                .maxBodySize(maxResponseBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .followRedirects(false)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .headers(request.headers)
                .method(Connection.Method.GET)

            if (recoveryAttempt) {
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

        val directResult = runCatching {
            try {
                execute(recoveryAttempt = false)
            } catch (t: Throwable) {
                if (!shouldRetryTruncatedResponse(t)) throw t
                SeriesDiagnosticLog.w("NET RETRY truncated-response host=$requestHost url=${request.url}")
                execute(recoveryAttempt = true)
            }
        }

        directResult.getOrNull()?.let { response ->
            val headers = response.multiHeaders().mapValues { (_, values) -> values.toList() }
            storeCookies(request.url, headers)
            return PluginHttpResponse(
                statusCode = response.statusCode(),
                finalUrl = response.url().toString(),
                body = response.body(),
                headers = headers
            )
        }

        val directError = directResult.exceptionOrNull() ?: error("Missing network error")
        if (!isNetworkFailure(directError)) throw directError

        var lastError: Throwable = directError
        if (shouldRetryWithDoh(directError)) {
            SeriesDiagnosticLog.w("NET RETRY doh host=$requestHost reason=${networkFailureName(directError)} url=${request.url}")
            val dohResult = runCatching {
                DohHttpFallback.get(
                    request = request,
                    maxResponseBytes = maxResponseBytes,
                    userAgent = userAgent,
                    origin = origin,
                    cookies = cookies,
                    timeoutMs = timeoutMs
                )
            }
            dohResult.getOrNull()?.let { fallback ->
                storeCookies(request.url, fallback.headers)
                SeriesDiagnosticLog.i("NET DOH GET ${request.url} -> ${fallback.statusCode}, ${fallback.body.length}b")
                return fallback
            }
            lastError = dohResult.exceptionOrNull() ?: lastError
        }

        val proxy = NetworkFallbackSettings.current()
        if (proxy.isUsable && shouldRetryWithProxy(lastError)) {
            SeriesDiagnosticLog.w(
                "NET RETRY proxy type=${proxy.type.name} proxy=${proxy.host}:${proxy.port} " +
                    "target=$requestHost reason=${networkFailureName(lastError)}"
            )
            val fallback = ProxyHttpFallback.get(
                request = request,
                maxResponseBytes = maxResponseBytes,
                userAgent = userAgent,
                origin = origin,
                cookies = cookies,
                config = proxy,
                timeoutMs = timeoutMs
            )
            storeCookies(request.url, fallback.headers)
            SeriesDiagnosticLog.i("NET PROXY GET ${request.url} -> ${fallback.statusCode}, ${fallback.body.length}b")
            return fallback
        }

        throw lastError
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

    internal fun requestTimeoutMs(url: String): Int {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        return if (host.equals("api.fantlab.ru", ignoreCase = true)) FANTLAB_TIMEOUT_MS else DEFAULT_TIMEOUT_MS
    }

    internal fun shouldRetryTruncatedResponse(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is EOFException }

    /** DoH changes name resolution only; retrying an already-resolved blocked IP through DoH wastes time. */
    internal fun shouldRetryWithDoh(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any { it is UnknownHostException }

    internal fun shouldRetryWithProxy(error: Throwable): Boolean = isNetworkFailure(error)

    internal fun isNetworkFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is UnknownHostException ||
                it is SocketException ||
                it is SocketTimeoutException
        }

    internal fun networkFailureName(error: Throwable): String =
        generateSequence(error) { it.cause }
            .firstOrNull {
                it is UnknownHostException ||
                    it is SocketException ||
                    it is SocketTimeoutException
            }
            ?.javaClass
            ?.simpleName
            ?: error.javaClass.simpleName
}
