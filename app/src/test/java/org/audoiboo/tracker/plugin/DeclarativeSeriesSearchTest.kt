package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DeclarativeSeriesSearchTest {
    @Test
    fun encodesQueryAndParsesCandidatesInsideSandbox() = withTempDir { root ->
        File(root, "search.rule").writeText("search")
        var requestedUrl = ""
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            requestedUrl = request.url
            PluginHttpResponse(
                200,
                request.url,
                """
                    <article><h2><a href='/book/1'>Star Blood One</a></h2><a class='author'>Author A</a></article>
                    <article><h2><a href='/book/2'>Star Blood Two</a></h2><a class='author'>Author A</a></article>
                """.trimIndent()
            )
        })
        val runtime = DeclarativePluginRuntime(sandbox, DeclarativeEntrypointDecoder {
            DeclarativeEntrypoint.SeriesSearch(
                searchUrl = "https://example.org/search?story={query}",
                items = RepeatedFields(
                    item = "article",
                    title = "h2 a@text",
                    link = "h2 a@href",
                    author = ".author"
                ),
                maxResults = 5
            )
        })
        val manifest = PluginPackageManifest(
            id = "search-test",
            name = "Search test",
            version = 1,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            runtime = PluginRuntime.DECLARATIVE,
            hosts = setOf("example.org"),
            capabilities = setOf(SourceCapability.SERIES_SEARCH),
            permissions = PluginPermissions(networkHosts = setOf("example.org")),
            entrypoints = mapOf("seriesSearch" to "search.rule")
        )

        val results = runtime.searchSeries(manifest, root, SeriesSearchQuery("Star Blood 10"))

        assertEquals("https://example.org/search?story=Star+Blood+10", requestedUrl)
        assertEquals(2, results.size)
        assertEquals("https://example.org/book/1", results[0].series.url)
        assertEquals(listOf("Author A"), results[0].series.authors.map { it.name })
        assertTrue(results.all { it.series.sourceId == "search-test" })
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-series-search-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
