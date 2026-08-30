package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesBookMembershipPolicyTest {
    private val series = SourceSeries(
        sourceId = "baza-knig",
        url = "https://baza-knig.info/series-1-zvezdnaya-krov",
        title = "Звездная Кровь"
    )

    @Test
    fun sameSeriesIsAccepted() {
        val book = SourceBook(
            sourceId = "baza-knig",
            url = "https://baza-knig.info/audio-1",
            title = "Звёздная Кровь 08. Истинный",
            seriesTitle = "Звёздная Кровь"
        )

        assertTrue(SeriesBookMembershipPolicy.belongsTo(series, book))
    }

    @Test
    fun relatedWhiteDevilSeriesIsRejected() {
        val book = SourceBook(
            sourceId = "baza-knig",
            url = "https://baza-knig.info/audio-2",
            title = "Звездная Кровь. Белый Дьявол 01",
            seriesTitle = "Звездная Кровь. Белый Дьявол"
        )

        assertFalse(SeriesBookMembershipPolicy.belongsTo(series, book))
    }

    @Test
    fun missingSeriesMetadataDoesNotDropBook() {
        val book = SourceBook(
            sourceId = "legacy-source",
            url = "https://example.org/book",
            title = "Книга"
        )

        assertTrue(SeriesBookMembershipPolicy.belongsTo(series, book))
    }
}
