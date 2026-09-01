package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBookSearchTest {
    @Test
    fun federatesProvidersAndDeduplicatesSameBook() = runBlocking {
        val first = FakeBookSearchPlugin(
            "one",
            listOf(
                CatalogBookSearchHit(
                    CatalogBook("one", "1", "Звёздная кровь 10", listOf("Роман Прокофьев"), listOf("Звездная кровь"), 10.0),
                    0.94f
                )
            )
        )
        val richer = FakeBookSearchPlugin(
            "two",
            listOf(
                CatalogBookSearchHit(
                    CatalogBook("two", "2", "Звездная кровь 10", listOf("Роман Прокофьев"), listOf("Звездная кровь"), 10.0, 2026, "https://two.example/cover.jpg"),
                    0.98f
                )
            )
        )

        val hits = CatalogBookSearchEngine(SourcePluginRegistry(listOf(first, richer))).search("Звездная кровь 10")

        assertEquals(1, hits.size)
        assertEquals("two", hits.single().book.providerId)
        assertEquals(0.98f, hits.single().confidence)
    }

    @Test
    fun brokenProviderDoesNotHideHealthyResults() = runBlocking {
        val good = FakeBookSearchPlugin(
            "good",
            listOf(CatalogBookSearchHit(CatalogBook("good", "1", "Book One", listOf("Author")), 1f))
        )
        val broken = FakeBookSearchPlugin("broken", emptyList(), broken = true)

        val hits = CatalogBookSearchEngine(SourcePluginRegistry(listOf(broken, good))).search("Book One")

        assertEquals(listOf("good"), hits.map { it.book.providerId })
    }

    @Test
    fun builtInCatalogsExposeBookSearchContract() {
        val providers = listOf(OpenLibraryMetadataPlugin, GoogleBooksCatalogPlugin, FantLabCatalogPlugin)
        providers.forEach { plugin ->
            assertTrue(SourceCapability.BOOK_SEARCH in plugin.descriptor.capabilities)
            assertTrue(plugin is CatalogBookSearchProvider)
        }
    }

    @Test
    fun titleConfidenceNormalizesYoAndFormatting() {
        assertEquals(1f, catalogTitleConfidence("Звездная кровь 10", "Звёздная кровь 10"))
        assertTrue(catalogTitleConfidence("Star Blood 10", "Star Blood. Book 10") >= 0.66f)
    }

    private class FakeBookSearchPlugin(
        private val id: String,
        private val hits: List<CatalogBookSearchHit>,
        private val broken: Boolean = false
    ) : SourcePlugin, CatalogBookSearchProvider {
        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf("$id.example"),
            capabilities = setOf(SourceCapability.BOOK_SEARCH)
        )

        override fun supports(url: String): Boolean = false

        override suspend fun searchBooks(query: String, limit: Int): List<CatalogBookSearchHit> {
            if (broken) error("boom")
            return hits.take(limit)
        }
    }
}
