package org.audoiboo.tracker.plugin

import java.net.URI

/** Limits enforced by the host for every external plugin invocation. */
data class PluginSandboxLimits(
    val maxRequestsPerInvocation: Int = 32,
    val maxRedirectsPerRequest: Int = 8,
    val maxResponseBytes: Long = 8L * 1024 * 1024,
    val maxOutputItems: Int = 1000
) {
    init {
        require(maxRequestsPerInvocation > 0)
        require(maxRedirectsPerRequest >= 0)
        require(maxResponseBytes > 0)
        require(maxOutputItems > 0)
    }
}

data class PluginHttpRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap()
)

data class PluginHttpResponse(
    val statusCode: Int,
    val finalUrl: String,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap()
)

fun interface PluginHttpTransport {
    /** Executes exactly one request. Redirect following belongs to PluginSandboxSession. */
    fun get(request: PluginHttpRequest, maxResponseBytes: Long): PluginHttpResponse
}

/**
 * Host-owned network gateway. Package plugins never receive Android Context, files,
 * sockets, processes or a raw HTTP client; every request is checked here first.
 */
class PluginSandboxSession internal constructor(
    private val manifest: PluginPackageManifest,
    private val transport: PluginHttpTransport,
    private val limits: PluginSandboxLimits
) {
    private var requestCount = 0

    @Synchronized
    fun httpGet(url: String, headers: Map<String, String> = emptyMap()): PluginHttpResponse {
        val safeHeaders = sanitizeHeaders(headers)
        var currentUrl = url
        var redirects = 0

        while (true) {
            requirePermittedUrl(currentUrl)
            if (requestCount >= limits.maxRequestsPerInvocation) {
                throw PluginSandboxViolation("Network request budget exceeded")
            }
            requestCount++

            val response = transport.get(PluginHttpRequest(currentUrl, safeHeaders), limits.maxResponseBytes)
            requirePermittedUrl(response.finalUrl)
            if (response.body.toByteArray(Charsets.UTF_8).size.toLong() > limits.maxResponseBytes) {
                throw PluginSandboxViolation("Response exceeds sandbox byte limit")
            }

            if (response.statusCode !in REDIRECT_CODES) return response
            if (redirects >= limits.maxRedirectsPerRequest) {
                throw PluginSandboxViolation("Redirect limit exceeded")
            }
            val location = response.headerValue("location")
                ?: throw PluginSandboxViolation("Redirect response has no Location header")
            currentUrl = runCatching { URI(response.finalUrl).resolve(location).toString() }
                .getOrElse { throw PluginSandboxViolation("Invalid redirect URL") }
            // Validate before the next transport call so an untrusted package can never cause
            // the host HTTP client to contact an undeclared domain.
            requirePermittedUrl(currentUrl)
            redirects++
        }
    }

    fun requireOutputSize(size: Int) {
        if (size > limits.maxOutputItems) throw PluginSandboxViolation("Plugin output item limit exceeded")
    }

    private fun requirePermittedUrl(url: String) {
        val host = validatedHost(url)
        if (host !in manifest.permissions.networkHosts) {
            throw PluginSandboxViolation("Network access to $host is not permitted")
        }
    }

    private fun validatedHost(url: String): String {
        val uri = runCatching { URI(url) }.getOrElse { throw PluginSandboxViolation("Invalid URL") }
        if (uri.scheme?.lowercase() !in setOf("http", "https")) throw PluginSandboxViolation("Only HTTP(S) URLs are allowed")
        if (uri.userInfo != null) throw PluginSandboxViolation("URLs containing credentials are not allowed")
        return uri.host?.lowercase()?.trimEnd('.') ?: throw PluginSandboxViolation("URL has no host")
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> {
        val blocked = setOf("host", "connection", "content-length", "cookie", "authorization", "proxy-authorization")
        return headers.filterKeys { it.lowercase() !in blocked }
    }

    private fun PluginHttpResponse.headerValue(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

class PluginSandboxViolation(message: String) : SecurityException(message)

/** Creates short-lived sessions and keeps policy outside plugin implementations. */
class PluginSandbox(
    private val transport: PluginHttpTransport,
    private val limits: PluginSandboxLimits = PluginSandboxLimits()
) {
    fun open(manifest: PluginPackageManifest): PluginSandboxSession {
        val validation = PluginPackagePolicy.validate(manifest)
        if (!validation.valid) throw PluginSandboxViolation(validation.errors.joinToString("; "))
        return PluginSandboxSession(manifest, transport, limits)
    }
}
