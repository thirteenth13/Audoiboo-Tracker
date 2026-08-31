package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBookAvailabilityResolverTest {
    @Test
    fun `matches source books back to individual catalog books`() {
        val author = CatalogAuthor("open-library", "OL1A", "Роман Прокофьев")
        val books = listOf(
            CatalogBook("open-library", "OL1W", "Звездная кровь 1", listOf(author.name), listOf("Звездная кровь"), 1.0),
            CatalogBook("open-library", "OL2W", "Звездная кровь 2", listOf(author.name), listOf("Звездная кровь"), 2.0)
        )
        val series = CatalogSeries("Звездная кровь", listOf(author.name), books)
        val canonical = CatalogCanonicalMapper.toCanonical("open-library", author, series)
        val sourceSeries = SourceSeries(
            sourceId = "audio-source",
            url = "https://audio.test/series",
            title = "Звездная кровь",
            authors = listOf(SourceAuthor(author.name))
        )
        val sourceBooks = listOf(
            SourceBook("audio-source", url = "https://audio.test/1", title = "Звездная кровь 1", authors = listOf(SourceAuthor(author.name)), seriesNumber = 1.0),
            SourceBook("audio-source", url = "https://audio.test/2", title = "Звездная кровь 2", authors = listOf(SourceAuthor(author.name)), seriesNumber = 2.0)
        )
        val match = CatalogSourceMatch(
            catalogProviderId = "open-library",
            author = author,
            series = series,
            canonical = canonical,
            sources = listOf(
                SeriesDiscoveryFinding(
                    sourceId = "audio-source",
                    series = sourceSeries,
                    books = sourceBooks,
                    confidence = 1f,
                    disposition = MatchDisposition.AUTO_ACCEPT,
                    evidence = listOf("exact series title")
                )
            )
        )

        val availability = CatalogBookAvailabilityResolver.resolve(match)

        assertEquals(2, availability.size)
        assertEquals("https://audio.test/1", availability[0].sources.single().sourceBook.url)
        assertEquals("https://audio.test/2", availability[1].sources.single().sourceBook.url)
        assertTrue(availability.all { it.sources.single().disposition == MatchDisposition.AUTO_ACCEPT })
    }

    @Test
    fun `does not report unrelated books as available`() {
        val author = CatalogAuthor("open-library", "OL1A", "Автор")
        val book = CatalogBook("open-library", "OL1W", "Первая книга", listOf(author.name), listOf("Цикл"), 1.0)
        val series = CatalogSeries("Цикл", listOf(author.name), listOf(book))
        val canonical = CatalogCanonicalMapper.toCanonical("open-library", author, series)
        val match = CatalogSourceMatch(
            catalogProviderId = "open-library",
            author = author,
            series = series,
            canonical = canonical,
            sources = listOf(
                SeriesDiscoveryFinding(
                    sourceId = "audio-source",
                    series = SourceSeries("audio-source", url = "https://audio.test/s", title = "Цикл"),
                    books = listOf(SourceBook("audio-source", url = "https://audio.test/x", title = "Совсем другая книга")),
                    confidence = 0.96f,
                    disposition = MatchDisposition.AUTO_ACCEPT,
                    evidence = emptyList()
                )
            )
        )

        assertTrue(CatalogBookAvailabilityResolver.resolve(match).single().sources.isEmpty())
    }
}
