package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DeclarativeSourcePluginHealthTest {
    @Test
    fun runtimeExceptionIsReportedWithoutBeingSwallowed() = withTempDir { root ->
        File(root, "series.rule").writeText("bad")
        val manifest = PluginPackageManifest(
            id = "health-test",
            name = "Health test",
            version = 4,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            hosts = setOf("example.org"),
            capabilities = setOf(SourceCapability.SERIES_LOOKUP),
            permissions = PluginPermissions(networkHosts = setOf("example.org")),
            entrypoints = mapOf("seriesLookup" to "series.rule")
        )
        val runtime = DeclarativePluginRuntime(
            PluginSandbox(PluginHttpTransport { request, _ -> PluginHttpResponse(200, request.url, "<h1>Series</h1>") }),
            DeclarativeEntrypointDecoder { error("broken parser") }
        )
        var failures = 0
        val plugin = DeclarativeSourcePlugin(
            manifest,
            root,
            runtime,
            onRuntimeFailure = { id, version, _ ->
                assertEquals("health-test", id)
                assertEquals(4, version)
                failures++
            }
        )

        val result = runCatching { runBlocking { plugin.resolveSeries("https://example.org/series") } }

        assertTrue(result.isFailure)
        assertEquals(1, failures)
    }

    @Test
    fun successfulRuntimeCallResetsHealthThroughCallback() = withTempDir { root ->
        File(root, "series.rule").writeText("ok")
        val manifest = PluginPackageManifest(
            id = "health-test",
            name = "Health test",
            version = 1,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            hosts = setOf("example.org"),
            capabilities = setOf(SourceCapability.SERIES_LOOKUP),
            permissions = PluginPermissions(networkHosts = setOf("example.org")),
            entrypoints = mapOf("seriesLookup" to "series.rule")
        )
        val runtime = DeclarativePluginRuntime(
            PluginSandbox(PluginHttpTransport { request, _ -> PluginHttpResponse(200, request.url, "<h1>Series</h1>") }),
            DeclarativeEntrypointDecoder { DeclarativeEntrypoint.SeriesLookup("h1") }
        )
        var successes = 0
        val plugin = DeclarativeSourcePlugin(
            manifest,
            root,
            runtime,
            onRuntimeSuccess = { _, _ -> successes++ }
        )

        val series = runBlocking { plugin.resolveSeries("https://example.org/series") }

        assertEquals("Series", series?.title)
        assertEquals(1, successes)
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-source-health-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
