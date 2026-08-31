package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogAudioSourceSelectorTest {
    @Test
    fun `prefers source with more matched books over slightly higher series confidence`() {
        val match = catalogMatch(
            findings = listOf(
                finding("partial", 0.99f, listOf(1)),
                finding("complete", 0.96f, listOf(1, 2, 3))
            )
        )

        val best = CatalogAudioSourceSelector.best(match)

        assertEquals("complete", best?.finding?.sourceId)
        assertEquals(3, best?.matchedBooks)
    }

    @Test
    fun `uses confidence as tie breaker when coverage is equal`() {
        val match = catalogMatch(
            findings = listOf(
                finding("lower", 0.95f, listOf(1, 2)),
                finding("higher", 0.98f, listOf(1, 2))
            )
        )

        assertEquals("higher", CatalogAudioSourceSelector.best(match)?.finding?.sourceId)
    }

    @Test
    fun `ignores review findings for automatic import`() {
        val auto = finding("auto", 0.95f, listOf(1), MatchDisposition.AUTO_ACCEPT)
        val review = finding("review", 0.99f, listOf(1, 2, 3), MatchDisposition.REVIEW)
        val match = catalogMatch(listOf(review, auto))

        assertEquals("auto", CatalogAudioSourceSelector.best(match)?.finding?.sourceId)
    }

    private fun catalogMatch(findings: List<SeriesDiscoveryFinding>): CatalogSourceMatch {
        val author = CatalogAuthor("open-library", "OL1A", "Автор")
        val books = (1..3).map { number ->
            CatalogBook(
                providerId = "open-library",
                remoteId = "OL${number}W",
                title = "Цикл $number",
                authors = listOf(author.name),
                seriesTitles = listOf("Цикл"),
                seriesNumber = number.toDouble()
            )
        }
        val series = CatalogSeries("Цикл", listOf(author.name), books)
        return CatalogSourceMatch(
            catalogProviderId = "open-library",
            author = author,
            series = series,
            canonical = CatalogCanonicalMapper.toCanonical("open-library", author, series),
            sources = findings
        )
    }

    private fun finding(
        sourceId: String,
        confidence: Float,
        volumes: List<Int>,
        disposition: MatchDisposition = MatchDisposition.AUTO_ACCEPT
    ): SeriesDiscoveryFinding = SeriesDiscoveryFinding(
        sourceId = sourceId,
        series = SourceSeries(sourceId, url = "https://$sourceId.test/series", title = "Цикл"),
        books = volumes.map { number ->
            SourceBook(
                sourceId = sourceId,
                url = "https://$sourceId.test/$number",
                title = "Цикл $number",
                seriesNumber = number.toDouble()
            )
        },
        confidence = confidence,
        disposition = disposition,
        evidence = emptyList()
    )
}
