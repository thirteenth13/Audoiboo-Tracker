package org.audoiboo.tracker.plugin.flibusta

import kotlinx.coroutines.runBlocking
import org.audoiboo.tracker.plugin.BookProvider
import org.audoiboo.tracker.plugin.BuiltInSourcePluginManager
import org.audoiboo.tracker.plugin.SeriesDiscoveryProvider
import org.audoiboo.tracker.plugin.SeriesSearchProvider
import org.audoiboo.tracker.plugin.SeriesSearchQuery
import org.audoiboo.tracker.plugin.SourceCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlibustaSourcePluginTest {
    @Test fun builtInRegistryExposesFlibustaDiscovery() {
        val plugin = BuiltInSourcePluginManager.registry.byId(FlibustaSourcePlugin.ID)

        assertNotNull(plugin)
        assertTrue(plugin is SeriesSearchProvider)
        assertTrue(plugin is SeriesDiscoveryProvider)
        assertTrue(plugin is BookProvider)
        assertTrue(SourceCapability.SERIES_SEARCH in plugin!!.descriptor.capabilities)
        assertTrue(SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities)
        assertTrue(SourceCapability.BOOK_LOOKUP in plugin.descriptor.capabilities)
    }

    @Test fun supportsAllThreeFlibustaHostsOnly() {
        val plugin = FlibustaSourcePlugin { _, _ -> error("network not expected") }

        assertTrue(plugin.supports("https://flibusta.site/b/1"))
        assertTrue(plugin.supports("https://flibusta.one/books/1-book/"))
        assertTrue(plugin.supports("https://flibusta.name/books/1-book/"))
        assertFalse(plugin.supports("https://example.org/b/1"))
    }

    @Test fun searchUsesClassicEndpointAndReturnsSeriesCandidate() = runBlocking {
        val html = """
            <html><body><div class="book-item">
              <a href="/a/77">Роман Прокофьев</a>
              <a href="/s/88">Звездная кровь</a>
              <span>#8</span>
              <a href="/b/61205">Звездная кровь 8. Истинный</a>
            </div></body></html>
        """.trimIndent()
        val requested = mutableListOf<String>()
        val plugin = FlibustaSourcePlugin { url, _ ->
            requested += url
            FlibustaHttpResponse(200, url, mapOf("Content-Type" to listOf("text/html")), html.toByteArray())
        }

        val result = plugin.searchSeries(SeriesSearchQuery("Звездная кровь"))

        assertEquals(1, result.size)
        assertEquals("Звездная кровь", result.single().series.title)
        assertEquals("61205", result.single().series.books.single().remoteId)
        assertTrue(requested.single().startsWith("https://flibusta.site/booksearch?ask="))
    }

    @Test fun searchUrlsAreVariantSpecificAndEncoded() {
        assertEquals(
            "https://flibusta.site/booksearch?ask=%D0%97%D0%B2%D0%B5%D0%B7%D0%B4%D0%BD%D0%B0%D1%8F+%D0%BA%D1%80%D0%BE%D0%B2%D1%8C",
            FlibustaSourcePlugin.searchUrl(FlibustaVariant.SITE, "Звездная кровь")
        )
        assertTrue(FlibustaSourcePlugin.searchUrl(FlibustaVariant.ONE, "a b").endsWith("/search/?q=a+b"))
        assertTrue(FlibustaSourcePlugin.searchUrl(FlibustaVariant.NAME, "a b").endsWith("/search/?q=a+b"))
    }
}
