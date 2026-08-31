package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SourceDiscoveryConcurrencyTest {
    @Test
    fun independentAudioSourcesRunConcurrently() = runBlocking {
        val gate = ConcurrentStartGate(expected = 2)
        val engine = SourceDiscoveryEngine(
            SourcePluginRegistry(
                listOf(
                    CoordinatedAudioPlugin("audio-one", gate),
                    CoordinatedAudioPlugin("audio-two", gate)
                )
            )
        )

        val findings = withTimeout(5_000) {
            engine.discoverSeries(canonical())
        }

        assertEquals(setOf("audio-one", "audio-two"), findings.map { it.sourceId }.toSet())
        assertEquals(2, gate.startedCount())
    }

    @Test
    fun brokenAudioSourceDoesNotCancelHealthySource() = runBlocking {
        val engine = SourceDiscoveryEngine(
            SourcePluginRegistry(
                listOf(
                    CoordinatedAudioPlugin("good"),
                    CoordinatedAudioPlugin("broken", broken = true)
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

    private class ConcurrentStartGate(private val expected: Int) {
        private val started = AtomicInteger(0)
        private val allStarted = CompletableDeferred<Unit>()

        suspend fun arriveAndAwait() {
            if (started.incrementAndGet() >= expected) allStarted.complete(Unit)
            allStarted.await()
        }

        fun startedCount(): Int = started.get()
    }

    private class CoordinatedAudioPlugin(
        private val id: String,
        private val gate: ConcurrentStartGate? = null,
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
            gate?.arriveAndAwait()
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
            if (broken) error("broken source")
            return listOf(
                SourceBook(id, url = "https://$id.example.org/book/1", title = "Book One", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 1.0),
                SourceBook(id, url = "https://$id.example.org/book/2", title = "Book Two", authors = listOf(SourceAuthor("Author A")), seriesTitle = "Star Blood", seriesNumber = 2.0)
            )
        }
    }
}
