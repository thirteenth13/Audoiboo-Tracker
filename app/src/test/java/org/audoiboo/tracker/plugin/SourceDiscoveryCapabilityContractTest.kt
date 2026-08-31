package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiscoveryCapabilityContractTest {
    @Test
    fun `source discovery ignores lookup-only plugins`() = runBlocking {
        val searchable = SearchableAudioPlugin()
        val lookupOnly = LookupOnlyAudioPlugin()
        val registry = SourcePluginRegistry(listOf(searchable, lookupOnly))
        val engine = SourceDiscoveryEngine(registry)

        val findings = engine.discoverSeries(
            CanonicalSeriesMatchInput(
                id = "catalog:series",
                title = "Star Blood",
                authors = listOf("Author A"),
                books = listOf(
                    CanonicalBookMatchInput("catalog:b1", "Book One", listOf("Author A"), 1.0)
                )
            )
        )

        assertEquals(listOf("searchable"), findings.map { it.sourceId })
        assertTrue(SourceCapability.SERIES_SEARCH !in lookupOnly.descriptor.capabilities)
        assertTrue(SourceCapability.SERIES_LOOKUP in lookupOnly.descriptor.capabilities)
    }

    @Test
    fun `package capability matrix keeps izib lookup only`() {
        val expectedSearchable = setOf("baza-knig", "knigavuhe", "poleknig", "lis10book")
        val expectedLookupOnly = setOf("izib")

        assertEquals(4, expectedSearchable.size)
        assertEquals(setOf("izib"), expectedLookupOnly)
        assertTrue(expectedSearchable.intersect(expectedLookupOnly).isEmpty())
    }

    private class SearchableAudioPlugin : SourcePlugin, SeriesSearchProvider, SeriesProvider {
        override val descriptor = SourceDescriptor(
            id = "searchable",
            name = "Searchable",
            version = 1,
            hosts = setOf("searchable.example.org"),
            capabilities = setOf(SourceCapability.SERIES_SEARCH, SourceCapability.SERIES_LOOKUP)
        )

        override fun supports(url: String): Boolean = url.contains("searchable.example.org")

        override suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate> = listOf(
            SeriesCandidate(
                SourceSeries(
                    sourceId = descriptor.id,
                    url = "https://searchable.example.org/series/star-blood",
                    title = "Star Blood",
                    authors = listOf(SourceAuthor("Author A"))
                )
            )
        )

        override suspend fun resolveSeries(url: String): SourceSeries = SourceSeries(
            sourceId = descriptor.id,
            url = url,
            title = "Star Blood",
            authors = listOf(SourceAuthor("Author A")),
            books = listOf(SourceBookRef(url = "https://searchable.example.org/book/1", title = "Book One", number = 1.0))
        )

        override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> = listOf(
            SourceBook(
                sourceId = descriptor.id,
                url = "https://searchable.example.org/book/1",
                title = "Book One",
                authors = listOf(SourceAuthor("Author A")),
                seriesTitle = "Star Blood",
                seriesNumber = 1.0
            )
        )
    }

    private class LookupOnlyAudioPlugin : SourcePlugin, SeriesProvider {
        override val descriptor = SourceDescriptor(
            id = "lookup-only",
            name = "Lookup Only",
            version = 1,
            hosts = setOf("lookup.example.org"),
            capabilities = setOf(SourceCapability.SERIES_LOOKUP, SourceCapability.BOOK_LOOKUP, SourceCapability.DOWNLOAD_RESOLUTION)
        )

        override fun supports(url: String): Boolean = url.contains("lookup.example.org")

        override suspend fun resolveSeries(url: String): SourceSeries? = null
        override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> = emptyList()
    }
}
