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
    @Test
    fun directDiscoveryKeepsSeriesCardsEvenWhenCanonicalMatcherRejectsOneTitle() {
        val root = createTempDirectory("lis10book-discovery-test-").toFile()
        try {
            val requested = mutableListOf<String>()
            val runtime = DeclarativePluginRuntime(
                PluginSandbox(PluginHttpTransport { request, _ ->
                    requested += request.url
                    PluginHttpResponse(
                        200,
                        request.url,
                        """
                        <main>
                          <a class='mcard' href='/audio/dlan-sistemy-kniga-1/'>
                            <span class='mcard-t'>Длань системы. Книга 1</span>
                            <span class='mcard-a'>Роман Прокофьев</span>
                          </a>
                          <a class='mcard' href='/audio/dlan-sistemy-kniga-2/'>
                            <span class='mcard-t'>Длань системы. Том второй</span>
                            <span class='mcard-a'>Роман Прокофьев</span>
                          </a>
                        </main>
                        """.trimIndent()
                    )
                })
            )
            val manifest = PluginPackageManifest(
                id = "lis10book",
                name = "Lis10book",
                version = 8,
                apiVersion = SOURCE_PLUGIN_API_VERSION,
                runtime = PluginRuntime.DECLARATIVE,
                hosts = setOf("lis10book.com"),
                capabilities = setOf(SourceCapability.SERIES_DISCOVERY),
                permissions = PluginPermissions(networkHosts = setOf("lis10book.com")),
                entrypoints = emptyMap()
            )
            val canonical = CanonicalSeriesMatchInput(
                id = "dlan",
                title = "Длань системы",
                authors = listOf("Роман Прокофьев"),
                books = listOf(
                    CanonicalBookMatchInput("1", "Длань системы. Книга 1", listOf("Роман Прокофьев"), 1.0),
                    CanonicalBookMatchInput("2", "Совсем другое сохраненное название", listOf("Роман Прокофьев"), 2.0)
                )
            )

            val result = runtime.discoverCanonicalSeries(manifest, canonical)

            assertEquals(1, requested.size)
            assertEquals("https://lis10book.com/serie/dlan-sistemy/", requested.single())
            assertEquals(2, result.single().series.books.size)
            assertEquals(2.0, result.single().series.books[1].number)
        } finally {
            root.deleteRecursively()
        }
    }

}
