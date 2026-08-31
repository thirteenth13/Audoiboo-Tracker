package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDiscoveryEndToEndTest {
    @Test
    fun catalogFlowsThroughSearchAndDirectDiscoveryAndRanksCoverage() = runBlocking {
        val registry = SourcePluginRegistry(
            listOf(
                FakeCatalog(),
                FakeAudioSearch("search-audio", listOf(1, 2)),
                FakeAudioDirect("direct-audio", listOf(1, 2, 3))
            )
        )

        val matches = CatalogSourceBridge(registry).discoverByAuthor("Author A")

        assertEquals(1, matches.size)
        val match = matches.single()
        assertEquals(setOf("search-audio", "direct-audio"), match.sources.map { it.sourceId }.toSet())
        assertTrue(match.sources.all { it.disposition == MatchDisposition.AUTO_ACCEPT })

        val ranked = CatalogAudioSourceSelector.rank(match)
        assertEquals("direct-audio", ranked.first().finding.sourceId)
        assertEquals(3, ranked.first().matchedBooks)
        assertEquals(3, ranked.first().totalBooks)

        val availability = CatalogBookAvailabilityResolver.resolve(match)
        assertEquals(3, availability.size)
        assertEquals(2, availability[0].sources.size)
        assertEquals(2, availability[1].sources.size)
        assertEquals(listOf("direct-audio"), availability[2].sources.map { it.sourceId })
    }

    private class FakeCatalog : SourcePlugin, AuthorCatalogProvider {
        override val descriptor = SourceDescriptor(
            id = "catalog",
            name = "Catalog",
            version = 1,
            hosts = setOf("catalog.example.org"),
            capabilities = setOf(SourceCapability.AUTHOR_CATALOG)
        )

        override fun supports(url: String) = false
        override suspend fun searchAuthors(query: String, limit: Int) = listOf(CatalogAuthor("catalog", "a1", "Author A"))
        override suspend fun loadAuthorCatalog(author: CatalogAuthor, limit: Int) = AuthorCatalog(
            author,
            (1..3).map { number ->
                CatalogBook(
                    providerId = "catalog",
                    remoteId = "b$number",
                    title = "Star Blood $number",
                    authors = listOf("Author A"),
                    seriesTitles = listOf("Star Blood"),
                    seriesNumber = number.toDouble()
                )
            }
        )
    }

    private abstract class FakeAudioBase(
        private val id: String,
        private val volumes: List<Int>,
        capabilities: Set<SourceCapability>
    ) : SourcePlugin, SeriesProvider {
        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf("$id.example.org"),
            capabilities = capabilities + SourceCapability.SERIES_LOOKUP
        )

        override fun supports(url: String) = url.contains("$id.example.org")

        protected fun candidate() = SeriesCandidate(
            SourceSeries(id, url = "https://$id.example.org/series/star-blood", title = "Star Blood", authors = listOf(SourceAuthor("Author A")))
        )

        override suspend fun resolveSeries(url: String) = SourceSeries(
            id,
            url = url,
            title = "Star Blood",
            authors = listOf(SourceAuthor("Author A")),
            books = volumes.map { SourceBookRef(url = "https://$id.example.org/book/$it", title = "Star Blood $it", number = it.toDouble()) }
        )

        override suspend fun loadSeriesBooks(series: SourceSeries) = volumes.map {
            SourceBook(
                id,
                url = "https://$id.example.org/book/$it",
                title = "Star Blood $it",
                authors = listOf(SourceAuthor("Author A")),
                seriesTitle = "Star Blood",
                seriesNumber = it.toDouble()
            )
        }
    }

    private class FakeAudioSearch(id: String, volumes: List<Int>) :
        FakeAudioBase(id, volumes, setOf(SourceCapability.SERIES_SEARCH)), SeriesSearchProvider {
        override suspend fun searchSeries(query: SeriesSearchQuery) = listOf(candidate())
    }

    private class FakeAudioDirect(id: String, volumes: List<Int>) :
        FakeAudioBase(id, volumes, setOf(SourceCapability.SERIES_DISCOVERY)), SeriesDiscoveryProvider {
        override suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput) = listOf(candidate())
    }
}
