package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiscoveryBookSearchTest {
    @Test
    fun bookResultsAreHydratedAndLinkedWithoutSeriesPage() = runBlocking {
        val plugin = FakePoleknigBookSearch()
        val canonical = CanonicalSeriesMatchInput(
            id = "series-1",
            title = "Звездная Кровь",
            authors = listOf("Роман Прокофьев"),
            books = listOf(
                CanonicalBookMatchInput("book-9", "Ранг неизвестен", listOf("Роман Прокофьев"), 9.0)
            )
        )

        val findings = SourceDiscoveryEngine(SourcePluginRegistry(listOf(plugin))).discoverSeries(canonical)

        assertEquals(1, findings.size)
        val finding = findings.single()
        assertEquals("poleknig", finding.sourceId)
        assertEquals(MatchDisposition.AUTO_ACCEPT, finding.disposition)
        assertEquals(listOf("Ранг неизвестен"), finding.books.map { it.title })
        assertEquals("Звездная Кровь", finding.books.single().seriesTitle)
        assertEquals(9.0, finding.books.single().seriesNumber)
        assertTrue(plugin.loadedUrls.none { it.contains("novelties") })
    }

    private class FakePoleknigBookSearch : SourcePlugin, SeriesSearchProvider, BookProvider {
        val loadedUrls = mutableListOf<String>()

        override val descriptor = SourceDescriptor(
            id = "poleknig",
            name = "Poleknig",
            version = 1,
            hosts = setOf("poleknig.com"),
            capabilities = setOf(SourceCapability.SERIES_SEARCH, SourceCapability.BOOK_LOOKUP)
        )

        override fun supports(url: String) = url.contains("poleknig.com")

        override suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate> = listOf(
            SeriesCandidate(SourceSeries("poleknig", url = "https://poleknig.com/books/novelties", title = "Все аудиокниги")),
            SeriesCandidate(SourceSeries("poleknig", url = "https://poleknig.com/books/212841", title = "Ранг неизвестен"))
        )

        override suspend fun loadBook(url: String): SourceBook? {
            loadedUrls += url
            return if (url.endsWith("/212841")) {
                SourceBook(
                    sourceId = "poleknig",
                    url = url,
                    title = "Ранг неизвестен",
                    authors = listOf(SourceAuthor("Роман Прокофьев")),
                    seriesNumber = 9.0
                )
            } else null
        }
    }
}
