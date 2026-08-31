package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiscoveryEngineTest {
    @Test
    fun findsHighConfidenceAlternateSourceAndIgnoresBrokenProvider() = runBlocking {
        val good = FakeSearchPlugin("good", broken = false)
        val broken = FakeSearchPlugin("broken", broken = true)
        val engine = SourceDiscoveryEngine(SourcePluginRegistry(listOf(broken, good)))
        val canonical = canonical()

        val results = engine.discoverSeries(canonical)

        assertEquals(1, results.size)
        assertEquals("good", results.single().sourceId)
        assertEquals(MatchDisposition.AUTO_ACCEPT, results.single().disposition)
        assertTrue(results.single().confidence >= SourceIdentityMatcher.AUTO_ACCEPT_THRESHOLD)
        assertEquals(2, results.single().books.size)
    }

    @Test
    fun discoversSourceWithoutTextSearchThroughDirectDiscoveryCapability() = runBlocking {
        val direct = FakeDirectDiscoveryPlugin("izib-like")
        val engine = SourceDiscoveryEngine(SourcePluginRegistry(listOf(direct)))

        val results = engine.discoverSeries(canonical())

        assertEquals(1, results.size)
        assertEquals("izib-like", results.single().sourceId)
        assertEquals(MatchDisposition.AUTO_ACCEPT, results.single().disposition)
        assertEquals(2, results.single().books.size)
        assertEquals(1, direct.discoveryCalls)
    }

    @Test
    fun deduplicatesSameCandidateReturnedByDirectDiscoveryAndSearch() = runBlocking {
        val hybrid = FakeHybridPlugin("hybrid")
        val engine = SourceDiscoveryEngine(SourcePluginRegistry(listOf(hybrid)))

        val results = engine.discoverSeries(canonical())

        assertEquals(1, results.size)
        assertEquals("hybrid", results.single().sourceId)
    }

    @Test
    fun canExcludeCurrentSource() = runBlocking {
        val source = FakeSearchPlugin("same", broken = false)
        val canonical = CanonicalSeriesMatchInput("c", "Star Blood")

        val results = SourceDiscoveryEngine(SourcePluginRegistry(listOf(source)))
            .discoverSeries(canonical, excludeSourceId = "same")

        assertTrue(results.isEmpty())
    }

    private fun canonical() = CanonicalSeriesMatchInput(
        id = "canonical-1",
        title = "Star Blood",
        authors = listOf("Author A"),
        books = listOf(
            CanonicalBookMatchInput("b1", "Book One", listOf("Author A"), 1.0),
            CanonicalBookMatchInput("b2", "Book Two", listOf("Author A"), 2.0)
        )
    )

    private open class FakeSearchPlugin(
        private val id: String,
        private val broken: Boolean
    ) : SourcePlugin, SeriesSearchProvider, SeriesProvider {
        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf("$id.example.org"),
            capabilities = setOf(SourceCapability.SERIES_SEARCH, SourceCapability.SERIES_LOOKUP)
        )

        override fun supports(url: String): Boolean = url.contains("$id.example.org")

        override suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate> {
            if (broken) error("broken search")
            return listOf(candidate(id))
        }

        override suspend fun resolveSeries(url: String): SourceSeries? = hydratedSeries(id)

        override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> = hydratedBooks(id)
    }

    private class FakeDirectDiscoveryPlugin(private val id: String) : SourcePlugin, SeriesDiscoveryProvider, SeriesProvider {
        var discoveryCalls = 0

        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf("$id.example.org"),
            capabilities = setOf(SourceCapability.SERIES_DISCOVERY, SourceCapability.SERIES_LOOKUP)
        )

        override fun supports(url: String): Boolean = url.contains("$id.example.org")

        override suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
            discoveryCalls++
            return listOf(candidate(id))
        }

        override suspend fun resolveSeries(url: String): SourceSeries? = hydratedSeries(id)

        override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> = hydratedBooks(id)
    }

    private class FakeHybridPlugin(private val id: String) : SourcePlugin, SeriesDiscoveryProvider, SeriesSearchProvider, SeriesProvider {
        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf("$id.example.org"),
            capabilities = setOf(SourceCapability.SERIES_DISCOVERY, SourceCapability.SERIES_SEARCH, SourceCapability.SERIES_LOOKUP)
        )

        override fun supports(url: String): Boolean = url.contains("$id.example.org")
        override suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> = listOf(candidate(id))
        override suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate> = listOf(candidate(id))
        override suspend fun resolveSeries(url: String): SourceSeries? = hydratedSeries(id)
        override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> = hydratedBooks(id)
    }

    companion object {
        private fun candidate(id: String) = SeriesCandidate(
            SourceSeries(
                sourceId = id,
                url = "https://$id.example.org/series/star-blood",
                title = "Star Blood",
                authors = listOf(SourceAuthor("Author A"))
            )
        )

        private fun hydratedSeries(id: String) = SourceSeries(
            sourceId = id,
            url = "https://$id.example.org/series/star-blood",
            title = "Star Blood",
            authors = listOf(SourceAuthor("Author A")),
            books = listOf(
                SourceBookRef(url = "https://$id.example.org/book/1", title = "Book One", number = 1.0),
                SourceBookRef(url = "https://$id.example.org/book/2", title = "Book Two", number = 2.0)
            )
        )

        private fun hydratedBooks(id: String) = listOf(
            SourceBook(id, url = "https://$id.example.org/book/1", title = "Book One", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 1.0),
            SourceBook(id, url = "https://$id.example.org/book/2", title = "Book Two", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 2.0)
        )
    }
}
