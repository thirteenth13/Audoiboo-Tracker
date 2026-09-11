package org.audoiboo.tracker.plugin

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogDiscoveryStageTimeoutTest {
    @Test
    fun successfulSearchAndLoadAreNotDroppedWhenCombinedDurationExceedsSingleStageTimeout() = runBlocking {
        val provider = SlowTwoStageCatalogPlugin(delayMs = 650)
        val engine = CatalogDiscoveryEngine(
            registry = SourcePluginRegistry(listOf(provider)),
            providerTimeoutMs = 1_000L
        )

        val started = System.nanoTime()
        val results = engine.discoverByAuthor("Author")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(1, results.size)
        assertEquals("slow", results.single().providerId)
        assertEquals("Cycle", results.single().series.single().title)
        assertTrue("test must exceed one stage timeout in total: ${elapsedMs}ms", elapsedMs >= 1_200L)
    }

    private class SlowTwoStageCatalogPlugin(
        private val delayMs: Long
    ) : SourcePlugin, AuthorCatalogProvider {
        override val descriptor = SourceDescriptor(
            id = "slow",
            name = "slow",
            version = 1,
            hosts = setOf("slow.example.org"),
            capabilities = setOf(SourceCapability.AUTHOR_CATALOG)
        )

        override fun supports(url: String): Boolean = url.contains("slow.example.org")

        override suspend fun searchAuthors(query: String, limit: Int): List<CatalogAuthor> {
            delay(delayMs)
            return listOf(CatalogAuthor("slow", "author-1", "Author", confidence = 1f))
        }

        override suspend fun loadAuthorCatalog(author: CatalogAuthor, limit: Int): AuthorCatalog {
            delay(delayMs)
            return AuthorCatalog(
                author,
                listOf(
                    CatalogBook(
                        providerId = "slow",
                        remoteId = "book-1",
                        title = "Cycle 1",
                        authors = listOf("Author")
                    )
                )
            )
        }
    }
}
