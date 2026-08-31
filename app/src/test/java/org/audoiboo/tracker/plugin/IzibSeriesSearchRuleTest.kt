package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class IzibSeriesSearchRuleTest {
    @Test
    fun parsesIzibSearchCardsAndBuildsArtCandidates() = withTempDir { root ->
        File(root, "search.json").writeText(
            """
            {
              "operation": "seriesSearch",
              "searchUrl": "https://izib.uk/search?q={query}&p=1",
              "maxResults": 10,
              "items": {
                "item": "#books_list > div > div",
                "title": "a[href^='/art']:not(:has(>img))",
                "link": "a[href^='/art']:not(:has(>img))@href",
                "author": "a[href^='/author']"
              }
            }
            """.trimIndent()
        )
        var requestedUrl = ""
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            requestedUrl = request.url
            PluginHttpResponse(
                200,
                request.url,
                """
                <div id='books_list'>
                  <div><div>
                    <a href='/art141591'><img src='/cover.jpg'></a>
                    <a href='/art141591'>Длань системы. Книга 3</a>
                    <a href='/author123'>Иван Автор</a>
                    <a href='/serie77'>Длань системы</a>
                  </div></div>
                </div>
                """.trimIndent()
            )
        })
        val runtime = DeclarativePluginRuntime(sandbox)
        val manifest = PluginPackageManifest(
            id = "izib",
            name = "Izib",
            version = 3,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            runtime = PluginRuntime.DECLARATIVE,
            hosts = setOf("izib.uk", "pda.izib.uk"),
            capabilities = setOf(SourceCapability.SERIES_SEARCH),
            permissions = PluginPermissions(networkHosts = setOf("izib.uk", "pda.izib.uk")),
            entrypoints = mapOf("seriesSearch" to "search.json")
        )

        val results = runtime.searchSeries(manifest, root, SeriesSearchQuery("Длань системы"))

        assertEquals("https://izib.uk/search?q=%D0%94%D0%BB%D0%B0%D0%BD%D1%8C+%D1%81%D0%B8%D1%81%D1%82%D0%B5%D0%BC%D1%8B&p=1", requestedUrl)
        assertEquals(1, results.size)
        assertEquals("Длань системы. Книга 3", results.single().series.title)
        assertEquals("https://izib.uk/art141591", results.single().series.url)
        assertEquals(listOf("Иван Автор"), results.single().series.authors.map { it.name })
        assertTrue(results.all { it.series.sourceId == "izib" })
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-izib-search-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
