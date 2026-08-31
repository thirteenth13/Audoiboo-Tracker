package org.audoiboo.tracker.plugin

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDiscoveryEngineTest {
    @Test
    fun infersSeriesAndNumberFromCommonAudiobookTitle() {
        val inferred = CatalogSeriesHeuristics.infer("Звездная кровь. Книга 10")

        assertEquals("Звездная кровь", inferred?.title)
        assertEquals(10.0, inferred?.number ?: -1.0, 0.0)
    }

    @Test
    fun groupsExplicitAndInferredSeriesAndKeepsStandaloneBooks() {
        val author = CatalogAuthor("catalog", "a1", "Author")
        val catalog = AuthorCatalog(
            author,
            listOf(
                CatalogBook("catalog", "b1", "Star Blood 1", listOf("Author")),
                CatalogBook("catalog", "b2", "Star Blood 2", listOf("Author")),
                CatalogBook("catalog", "b3", "Different title", listOf("Author"), seriesTitles = listOf("Other Cycle")),
                CatalogBook("catalog", "b4", "Standalone", listOf("Author"))
            )
        )

        val result = CatalogSeriesHeuristics.group(catalog)

        assertEquals(2, result.series.size)
        val starBlood = result.series.first { it.title == "Star Blood" }
        assertEquals(listOf(1.0, 2.0), starBlood.books.map { it.seriesNumber })
        assertEquals(listOf("Standalone"), result.standaloneBooks.map { it.title })
    }

    @Test
    fun discoveryUsesCatalogProvidersAndIsolatesBrokenOnes() = runBlocking {
        val engine = CatalogDiscoveryEngine(
            SourcePluginRegistry(listOf(FakeCatalogPlugin("good"), FakeCatalogPlugin("broken", broken = true)))
        )

        val results = engine.discoverByAuthor("Author")

        assertEquals(1, results.size)
        assertEquals("good", results.single().providerId)
        assertEquals("Author", results.single().author.name)
        assertEquals("Cycle", results.single().series.single().title)
        assertEquals(2, results.single().series.single().books.size)
    }

    @Test
    fun catalogProvidersRunConcurrently() = runBlocking {
        val engine = CatalogDiscoveryEngine(
            SourcePluginRegistry(
                listOf(
                    FakeCatalogPlugin("one", delayMs = 180),
                    FakeCatalogPlugin("two", delayMs = 180)
                )
            )
        )

        val started = System.nanoTime()
        val results = engine.discoverByAuthor("Author")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(2, results.size)
        assertTrue("catalog providers ran too slowly: ${elapsedMs}ms", elapsedMs < 650)
    }

    @Test
    fun blankAuthorDoesNotCallProviders() = runBlocking {
        val results = CatalogDiscoveryEngine(SourcePluginRegistry(listOf(FakeCatalogPlugin("good"))))
            .discoverByAuthor("   ")

        assertTrue(results.isEmpty())
    }

    private class FakeCatalogPlugin(
        private val id: String,
        private val broken: Boolean = false,
        private val delayMs: Long = 0
    ) : SourcePlugin, AuthorCatalogProvider {
        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf("$id.example.org"),
            capabilities = setOf(SourceCapability.AUTHOR_CATALOG)
        )

        override fun supports(url: String): Boolean = url.contains("$id.example.org")

        override suspend fun searchAuthors(query: String, limit: Int): List<CatalogAuthor> {
            if (delayMs > 0) delay(delayMs)
            if (broken) error("broken provider")
            return listOf(CatalogAuthor(id, "a1", "Author", confidence = 1f))
        }

        override suspend fun loadAuthorCatalog(author: CatalogAuthor, limit: Int): AuthorCatalog {
            if (delayMs > 0) delay(delayMs)
            if (broken) error("broken provider")
            return AuthorCatalog(
                author,
                listOf(
                    CatalogBook(id, "b1", "Cycle 1", listOf("Author")),
                    CatalogBook(id, "b2", "Cycle 2", listOf("Author"))
                )
            )
        }
    }
}
