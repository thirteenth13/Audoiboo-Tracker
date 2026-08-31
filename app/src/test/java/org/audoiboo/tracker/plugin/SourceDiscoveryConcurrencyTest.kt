package org.audoiboo.tracker.plugin

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiscoveryConcurrencyTest {
    @Test
    fun independentAudioSourcesRunConcurrently() = runBlocking {
        val engine = SourceDiscoveryEngine(
            SourcePluginRegistry(
                listOf(
                    SlowAudioPlugin("audio-one", 220),
                    SlowAudioPlugin("audio-two", 220)
                )
            )
        )

        val started = System.nanoTime()
        val findings = engine.discoverSeries(canonical())
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(setOf("audio-one", "audio-two"), findings.map { it.sourceId }.toSet())
        // Each source performs three delayed stages (search, resolve, load). Two sources in
        // series take roughly 1320 ms; concurrent source pipelines take roughly 660 ms.
        // Keep enough CI headroom without making the assertion so loose that serial execution passes.
        assertTrue("audio sources ran too slowly: ${elapsedMs}ms", elapsedMs < 1100)
    }

    @Test
    fun brokenAudioSourceDoesNotCancelHealthySource() = runBlocking {
        val engine = SourceDiscoveryEngine(
            SourcePluginRegistry(
                listOf(
                    SlowAudioPlugin("good", 0),
                    SlowAudioPlugin("broken", 0, broken = true)
                )
            )
        )

        val findings = engine.discoverSeries(canonical())

        assertEquals(listOf("good"), findings.map { it.sourceId })
    }

    private fun canonical() = CanonicalSeriesMatchInput(
        id = "catalog:a1:star-blood",
        title = "Star Blood",
        authors = listOf("Author A"),
        books = listOf(
            CanonicalBookMatchInput("catalog:b1", "Book One", listOf("Author A"), 1.0),
            CanonicalBookMatchInput("catalog:b2", "Book Two", listOf("Author A"), 2.0)
        )
    )

    private class SlowAudioPlugin(
        private val id: String,
        private val delayMs: Long,
        private val broken: Boolean = false
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
            if (delayMs > 0) delay(delayMs)
            if (broken) error("broken source")
            return listOf(
                SeriesCandidate(
                    SourceSeries(
                        sourceId = id,
                        url = "https://$id.example.org/series/star-blood",
                        title = "Star Blood",
                        authors = listOf(SourceAuthor("Author A"))
                    )
                )
            )
        }

        override suspend fun resolveSeries(url: String): SourceSeries? {
            if (delayMs > 0) delay(delayMs)
            if (broken) error("broken source")
            return SourceSeries(
                sourceId = id,
                url = url,
                title = "Star Blood",
                authors = listOf(SourceAuthor("Author A")),
                books = listOf(
                    SourceBookRef(url = "https://$id.example.org/book/1", title = "Book One", number = 1.0),
                    SourceBookRef(url = "https://$id.example.org/book/2", title = "Book Two", number = 2.0)
                )
            )
        }

        override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> {
            if (delayMs > 0) delay(delayMs)
            if (broken) error("broken source")
            return listOf(
                SourceBook(id, url = "https://$id.example.org/book/1", title = "Book One", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 1.0),
                SourceBook(id, url = "https://$id.example.org/book/2", title = "Book Two", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 2.0)
            )
        }
    }
}
