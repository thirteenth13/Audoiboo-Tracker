package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BazaAuthorDiscoveryPagingTest {
    @Test
    fun scansAuthorPagesUntilExactAuthorIsFound() {
        val manifest = PluginPackageManifest(
            id = "baza-knig",
            name = "Baza-Knig",
            version = 14,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            hosts = setOf("baza-knig.info"),
            capabilities = setOf(SourceCapability.SERIES_DISCOVERY),
            permissions = PluginPermissions(networkHosts = setOf("baza-knig.info"))
        )
        val requested = mutableListOf<String>()
        val runtime = DeclarativePluginRuntime(PluginSandbox(PluginHttpTransport { request, _ ->
            requested += request.url
            val body = when (request.url) {
                "https://baza-knig.info/index.php?do=search&subaction=search&story=%D0%A1%D0%B5%D1%80%D0%B3%D0%B5%D0%B9+%D0%A2%D0%B0%D1%80%D0%BC%D0%B0%D1%88%D0%B5%D0%B2" ->
                    return@PluginHttpTransport PluginHttpResponse(301, request.url, "")
                "https://baza-knig.info/authors/let-%D0%A2" -> """
                    <a href='/avtor-other-1'>Т Д</a>
                    <a href='?page=2'>2</a><a href='?page=3'>3</a><a href='?page=4'>4</a>
                """.trimIndent()
                "https://baza-knig.info/authors/let-%D0%A2?page=2" ->
                    "<a href='/avtor-other-2'>Таланов Алексей</a>"
                "https://baza-knig.info/authors/let-%D0%A2?page=3" ->
                    "<a href='/avtor-other-3'>Тарковская Марина А.</a>"
                "https://baza-knig.info/authors/let-%D0%A2?page=4" ->
                    "<a href='/avtor-tarmashev'>Тармашев Сергей Сергеевич</a>"
                "https://baza-knig.info/avtor-tarmashev" -> """
                    <article class='abook-item'>
                      <h2 class='abook-title'><a class='book-title' href='/audio-100-konec-tmy'>Конец Тьмы</a></h2>
                    </article>
                """.trimIndent()
                "https://baza-knig.info/avtor-tarmashev?page=2",
                "https://baza-knig.info/avtor-tarmashev?page=3" -> ""
                else -> error("unexpected ${request.url}")
            }
            PluginHttpResponse(200, request.url, body)
        }))

        val results = runtime.discoverCanonicalSeries(
            manifest,
            CanonicalSeriesMatchInput(
                id = "darkness",
                title = "Тьма",
                authors = listOf("Сергей Тармашев"),
                books = listOf(
                    CanonicalBookMatchInput(
                        id = "end-darkness",
                        title = "Конец Тьмы",
                        authors = listOf("Сергей Тармашев")
                    )
                )
            )
        )

        assertEquals(1, results.size)
        assertEquals(listOf("Конец Тьмы"), results.single().series.books.map { it.title })
        assertTrue(requested.contains("https://baza-knig.info/authors/let-%D0%A2?page=4"))
        assertTrue(requested.indexOf("https://baza-knig.info/authors/let-%D0%A2?page=3") < requested.indexOf("https://baza-knig.info/authors/let-%D0%A2?page=4"))
    }
}
