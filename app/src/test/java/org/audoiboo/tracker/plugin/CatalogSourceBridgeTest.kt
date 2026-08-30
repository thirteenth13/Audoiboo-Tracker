package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSourceBridgeTest {
    @Test
    fun mapsCatalogSeriesToCanonicalIdentity() {
        val author = CatalogAuthor("catalog", "a1", "Author A")
        val series = CatalogSeries(
            title = "Star Blood",
            authors = listOf("Author A"),
            books = listOf(
                CatalogBook("catalog", "b1", "Book One", listOf("Author A"), seriesNumber = 1.0),
                CatalogBook("catalog", "b2", "Book Two", listOf("Author A"), seriesNumber = 2.0)
            )
        )

        val canonical = CatalogCanonicalMapper.toCanonical("catalog", author, series)

        assertEquals("Star Blood", canonical.title)
        assertEquals(listOf(1.0, 2.0), canonical.books.map { it.number })
        assertTrue(canonical.id.contains("catalog:a1:star blood"))
    }

    @Test
    fun discoversAudioSourcesForCatalogSeries() = runBlocking {
        val catalog = FakeCatalogPlugin()
        val audio = FakeAudioSeriesPlugin()
        val bridge = CatalogSourceBridge(SourcePluginRegistry(listOf(catalog, audio)))

        val result = bridge.discoverByAuthor("Author A")

        assertEquals(1, result.size)
        val match = result.single()
        assertEquals("Star Blood", match.series.title)
        assertEquals(1, match.sources.size)
        assertEquals("audio", match.sources.single().sourceId)
        assertEquals(MatchDisposition.AUTO_ACCEPT, match.sources.single().disposition)
    }

    private class FakeCatalogPlugin : SourcePlugin, AuthorCatalogProvider {
        override val descriptor = SourceDescriptor(
            id = "catalog",
            name = "Catalog",
            version = 1,
            hosts = setOf("catalog.example.org"),
            capabilities = setOf(SourceCapability.AUTHOR_CATALOG)
        )

        override fun supports(url: String): Boolean = false

        override suspend fun searchAuthors(query: String, limit: Int): List<CatalogAuthor> =
            listOf(CatalogAuthor("catalog", "a1", "Author A"))

        override suspend fun loadAuthorCatalog(author: CatalogAuthor, limit: Int): AuthorCatalog =
            AuthorCatalog(
                author,
                listOf(
                    CatalogBook("catalog", "b1", "Book One", listOf("Author A"), seriesTitles = listOf("Star Blood"), seriesNumber = 1.0),
                    CatalogBook("catalog", "b2", "Book Two", listOf("Author A"), seriesTitles = listOf("Star Blood"), seriesNumber = 2.0)
                )
            )
    }

    private class FakeAudioSeriesPlugin : SourcePlugin, SeriesSearchProvider, SeriesProvider {
        override val descriptor = SourceDescriptor(
            id = "audio",
            name = "Audio",
            version = 1,
            hosts = setOf("audio.example.org"),
            capabilities = setOf(SourceCapability.SERIES_SEARCH, SourceCapability.SERIES_LOOKUP)
        )

        override fun supports(url: String): Boolean = url.contains("audio.example.org")

        override suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate> = listOf(
            SeriesCandidate(
                SourceSeries(
                    sourceId = "audio",
                    url = "https://audio.example.org/series/star-blood",
                    title = "Star Blood",
                    authors = listOf(SourceAuthor("Author A"))
                )
            )
        )

        override suspend fun resolveSeries(url: String): SourceSeries = SourceSeries(
            sourceId = "audio",
            url = url,
            title = "Star Blood",
            authors = listOf(SourceAuthor("Author A")),
            books = listOf(
                SourceBookRef(url = "https://audio.example.org/book/1", title = "Book One", number = 1.0),
                SourceBookRef(url = "https://audio.example.org/book/2", title = "Book Two", number = 2.0)
            )
        )

        override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> = listOf(
            SourceBook("audio", url = "https://audio.example.org/book/1", title = "Book One", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 1.0),
            SourceBook("audio", url = "https://audio.example.org/book/2", title = "Book Two", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 2.0)
        )
    }
}