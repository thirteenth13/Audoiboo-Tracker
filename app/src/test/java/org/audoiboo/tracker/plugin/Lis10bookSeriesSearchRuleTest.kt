package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class Lis10bookSeriesSearchRuleTest {
    @Test
    fun parsesAudioLinksFromSearchResults() {
        val root = createTempDirectory("lis10book-search-test-").toFile()
        try {
            val rules = File(root, "rules").apply { mkdirs() }
            File(rules, "search.json").writeText(
                """
                {
                  "operation": "seriesSearch",
                  "searchUrl": "https://lis10book.com/?s={query}",
                  "maxResults": 12,
                  "items": {
                    "item": "a[href*='/audio/']",
                    "title": "@text",
                    "link": "@href"
                  }
                }
                """.trimIndent()
            )
            var requestedUrl = ""
            val runtime = DeclarativePluginRuntime(
                PluginSandbox(PluginHttpTransport { request, _ ->
                    requestedUrl = request.url
                    PluginHttpResponse(
                        200,
                        request.url,
                        """
                        <main>
                          <a href='/audio/dlan-sistemy-kniga-3/'>Длань системы. Книга 3</a>
                          <a href='/audio/dlan-sistemy-kniga-4/'>Длань системы. Книга 4</a>
                          <a href='/serie/dlan-sistemy/'>Длань системы</a>
                        </main>
                        """.trimIndent()
                    )
                })
            )
            val manifest = PluginPackageManifest(
                id = "lis10book",
                name = "Lis10book",
                version = 3,
                apiVersion = SOURCE_PLUGIN_API_VERSION,
                runtime = PluginRuntime.DECLARATIVE,
                hosts = setOf("lis10book.com"),
                capabilities = setOf(SourceCapability.SERIES_SEARCH),
                permissions = PluginPermissions(networkHosts = setOf("lis10book.com")),
                entrypoints = mapOf("seriesSearch" to "rules/search.json")
            )

            val results = runtime.searchSeries(manifest, root, SeriesSearchQuery("Длань системы"))

            assertEquals("https://lis10book.com/?s=%D0%94%D0%BB%D0%B0%D0%BD%D1%8C+%D1%81%D0%B8%D1%81%D1%82%D0%B5%D0%BC%D1%8B", requestedUrl)
            assertEquals(2, results.size)
            assertEquals("https://lis10book.com/audio/dlan-sistemy-kniga-3/", results[0].series.url)
        } finally {
            root.deleteRecursively()
        }
    }
}
