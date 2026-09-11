package org.audoiboo.tracker.plugin

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Last-resort transport for provider requests when the Android/system network path cannot resolve
 * or connect to a host. DNS is resolved through Cloudflare DoH while TLS still uses the original
 * hostname, so certificate/SNI validation is preserved. Redirects remain disabled and continue to
 * be validated by PluginSandboxSession one hop at a time.
 */
internal object DohHttpFallback {
    private const val DOH_HOST = "cloudflare-dns.com"
    private const val DOH_URL = "https://cloudflare-dns.com/dns-query"
    private val bootstrapAddresses = listOf("1.1.1.1", "1.0.0.1").map(InetAddress::getByName)

    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.equals(DOH_HOST, ignoreCase = true)) bootstrapAddresses else Dns.SYSTEM.lookup(hostname)
    }

    private val dohClient = OkHttpClient.Builder()
        .dns(bootstrapDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val providerDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolve(hostname)
    }

    private val providerClient = OkHttpClient.Builder()
        .dns(providerDns)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun get(
        request: PluginHttpRequest,
        maxResponseBytes: Long,
        userAgent: String,
        origin: String?,
        cookies: String?
    ): PluginHttpResponse {
        val builder = Request.Builder().url(request.url).get()
            .header("User-Agent", userAgent)
            .header("Accept-Language", "ru-RU,ru;q=0.9,uk;q=0.8,en;q=0.6")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

        request.headers.forEach { (name, value) -> builder.header(name, value) }
        if (!origin.isNullOrBlank() && request.headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            builder.header("Referer", origin)
        }
        if (!cookies.isNullOrBlank() && request.headers.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            builder.header("Cookie", cookies)
        }

        providerClient.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.byteStream()?.use { input ->
                readLimited(input, maxResponseBytes)
            } ?: ByteArray(0)
            val body = bytes.toString(StandardCharsets.UTF_8)
            val headers = response.headers.names().associateWith { name -> response.headers.values(name) }
            return PluginHttpResponse(
                statusCode = response.code,
                finalUrl = response.request.url.toString(),
                body = body,
                headers = headers
            )
        }
    }

    internal fun resolve(hostname: String): List<InetAddress> {
        if (hostname.isBlank()) throw UnknownHostException("Blank hostname")
        if (runCatching { URI("https://$hostname").host }.getOrNull() == null) {
            throw UnknownHostException("Invalid hostname: $hostname")
        }

        val encoded = URLEncoder.encode(hostname, StandardCharsets.UTF_8.name())
        val request = Request.Builder()
            .url("$DOH_URL?name=$encoded&type=A")
            .header("Accept", "application/dns-json")
            .get()
            .build()

        dohClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw UnknownHostException("DoH HTTP ${response.code} for $hostname")
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse {
                throw UnknownHostException("Invalid DoH response for $hostname")
            }
            if (json.optInt("Status", -1) != 0) throw UnknownHostException("DoH status ${json.optInt("Status")} for $hostname")

            val answers = json.optJSONArray("Answer") ?: throw UnknownHostException("No DoH answer for $hostname")
            val result = buildList {
                for (index in 0 until answers.length()) {
                    val item = answers.optJSONObject(index) ?: continue
                    if (item.optInt("type") != 1) continue
                    val ip = item.optString("data").trim()
                    val parsed = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: continue
                    add(InetAddress.getByAddress(hostname, parsed.address))
                }
            }.distinctBy { it.hostAddress }
            if (result.isEmpty()) throw UnknownHostException("No IPv4 DoH answer for $hostname")
            SeriesDiagnosticLog.i("NET DOH resolved host=$hostname ips=${result.joinToString { it.hostAddress.orEmpty() }}")
            return result
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
