package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSandboxTest {
    private val manifest = PluginPackageManifest(
        id = "sandbox-test",
        name = "Sandbox test",
        version = 1,
        apiVersion = SOURCE_PLUGIN_API_VERSION,
        hosts = setOf("example.org"),
        capabilities = setOf(SourceCapability.SERIES_LOOKUP),
        permissions = PluginPermissions(networkHosts = setOf("example.org"))
    )

    @Test
    fun allowsDeclaredHostAndRemovesSensitiveHeaders() {
        var request: PluginHttpRequest? = null
        val sandbox = PluginSandbox(PluginHttpTransport { incoming, _ ->
            request = incoming
            PluginHttpResponse(200, incoming.url, "ok")
        })

        val response = sandbox.open(manifest).httpGet(
            "https://example.org/books",
            mapOf("Accept" to "text/html", "Cookie" to "secret", "Authorization" to "token")
        )

        assertEquals("ok", response.body)
        assertEquals("text/html", request!!.headers["Accept"])
        assertFalse(request!!.headers.keys.any { it.equals("Cookie", true) })
        assertFalse(request!!.headers.keys.any { it.equals("Authorization", true) })
    }

    @Test
    fun rejectsUndeclaredHostBeforeTransport() {
        var called = false
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            called = true
            PluginHttpResponse(200, request.url, "unexpected")
        })

        assertThrows(PluginSandboxViolation::class.java) {
            sandbox.open(manifest).httpGet("https://evil.example/file")
        }
        assertFalse(called)
    }

    @Test
    fun rejectsRedirectToUndeclaredHostBeforeSecondRequest() {
        var calls = 0
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            calls++
            PluginHttpResponse(
                statusCode = 302,
                finalUrl = request.url,
                body = "",
                headers = mapOf("Location" to listOf("https://cdn.example.net/archive.zip"))
            )
        })

        assertThrows(PluginSandboxViolation::class.java) {
            sandbox.open(manifest).httpGet("https://example.org/download")
        }
        assertEquals(1, calls)
    }

    @Test
    fun followsDeclaredRedirectThroughValidatedSecondHop() {
        val seen = mutableListOf<String>()
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            seen += request.url
            if (request.url.endsWith("/start")) {
                PluginHttpResponse(302, request.url, "", mapOf("Location" to listOf("/final")))
            } else {
                PluginHttpResponse(200, request.url, "ok")
            }
        })

        val response = sandbox.open(manifest).httpGet("https://example.org/start")

        assertEquals("ok", response.body)
        assertEquals(listOf("https://example.org/start", "https://example.org/final"), seen)
    }

    @Test
    fun enforcesRequestBudget() {
        val sandbox = PluginSandbox(
            PluginHttpTransport { request, _ -> PluginHttpResponse(200, request.url, "ok") },
            PluginSandboxLimits(maxRequestsPerInvocation = 1)
        )
        val session = sandbox.open(manifest)
        session.httpGet("https://example.org/one")

        assertThrows(PluginSandboxViolation::class.java) {
            session.httpGet("https://example.org/two")
        }
    }

    @Test
    fun rejectsCredentialsAndNonHttpSchemes() {
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ -> PluginHttpResponse(200, request.url, "ok") })
        val session = sandbox.open(manifest)

        assertThrows(PluginSandboxViolation::class.java) { session.httpGet("file:///data/local/tmp/x") }
        assertThrows(PluginSandboxViolation::class.java) { session.httpGet("https://user:pass@example.org/x") }
    }

    @Test
    fun enforcesOutputLimit() {
        val sandbox = PluginSandbox(
            PluginHttpTransport { request, _ -> PluginHttpResponse(200, request.url, "ok") },
            PluginSandboxLimits(maxOutputItems = 2)
        )
        val session = sandbox.open(manifest)
        session.requireOutputSize(2)
        assertTrue(true)
        assertThrows(PluginSandboxViolation::class.java) { session.requireOutputSize(3) }
    }
}
