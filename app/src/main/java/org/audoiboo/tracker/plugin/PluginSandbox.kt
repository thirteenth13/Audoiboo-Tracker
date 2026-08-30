package org.audoiboo.tracker.plugin

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/** Limits enforced by the host for every external plugin invocation. */
data class PluginSandboxLimits(
    val maxRequestsPerInvocation: Int = 32,
    val maxResponseBytes: Long = 8L * 1024 * 1024,
    val maxOutputItems: Int = 1000
) {
    init {
        require(maxRequestsPerInvocation > 0)
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
        if (requestCount >= limits.maxRequestsPerInvocation) {
            throw PluginSandboxViolation("Network request budget exceeded")
        }
        val host = validatedHost(url)
        if (host !in manifest.permissions.networkHosts) {
            throw PluginSandboxViolation("Network access to $host is not permitted")
        }
        requestCount++
        val response = transport.get(PluginHttpRequest(url, sanitizeHeaders(headers)), limits.maxResponseBytes)
        val finalHost = validatedHost(response.finalUrl)
        if (finalHost !in manifest.permissions.networkHosts) {
            throw PluginSandboxViolation("Redirected to unpermitted host $finalHost")
        }
        if (response.body.toByteArray(Charsets.UTF_8).size.toLong() > limits.maxResponseBytes) {
            throw PluginSandboxViolation("Response exceeds sandbox byte limit")
        }
        return response
    }

    fun requireOutputSize(size: Int) {
        if (size > limits.maxOutputItems) throw PluginSandboxViolation("Plugin output item limit exceeded")
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
