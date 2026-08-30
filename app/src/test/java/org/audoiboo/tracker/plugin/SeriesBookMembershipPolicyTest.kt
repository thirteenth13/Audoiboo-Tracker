package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesBookMembershipPolicyTest {
    private val series = SourceSeries(
        sourceId = "audioboo",
        url = "https://audioboo.org/series/zvezdnaya-krov",
        title = "Звездная Кровь"
    )

    @Test
    fun sameSeriesIsAccepted() {
        val book = SourceBook(
            sourceId = "audioboo",
            url = "https://audioboo.org/book-8",
            title = "Прокофьев Роман - Звёздная Кровь 08. Истинный",
            seriesTitle = "Звёздная Кровь"
        )

        assertTrue(SeriesBookMembershipPolicy.belongsTo(series, book))
        assertNull(SeriesBookMembershipPolicy.inferredNestedSeriesKey(series, book))
    }

    @Test
    fun titleWithoutParentSeriesPrefixIsAcceptedWhenDeclaredSeriesMatches() {
        val book = SourceBook(
            sourceId = "audioboo",
            url = "https://audioboo.org/book-11",
            title = "Колония Альфа - Прокофьев Роман",
            seriesTitle = "Звездная Кровь"
        )

        assertTrue(SeriesBookMembershipPolicy.belongsTo(series, book))
    }

    @Test
    fun explicitlyDifferentRelatedSeriesIsRejected() {
        val book = SourceBook(
            sourceId = "baza-knig",
            url = "https://baza-knig.info/audio-2",
            title = "Звездная Кровь. Белый Дьявол 01",
            seriesTitle = "Звездная Кровь. Белый Дьявол"
        )

        assertFalse(SeriesBookMembershipPolicy.belongsTo(series, book))
    }

    @Test
    fun flattenedWhiteDevilSeriesIsRejectedFromParentAndCanBeRehomed() {
        val book = SourceBook(
            sourceId = "audioboo",
            url = "https://audioboo.org/book-white-devil-2",
            title = "Прокофьев Роман - Звездная Кровь. Белый Дьявол 02. Лед-Кузнец",
            seriesTitle = "Звездная Кровь"
        )

        assertFalse(SeriesBookMembershipPolicy.belongsTo(series, book))
        assertEquals("звездная кровь белый дьявол", SeriesBookMembershipPolicy.inferredNestedSeriesKey(series, book))
        assertEquals(2, SeriesBookMembershipPolicy.inferredNestedVolumeNumber(series, book))
    }

    @Test
    fun anotherNamedSubseriesBeforeVolumeNumberIsRejectedGenerically() {
        val book = SourceBook(
            sourceId = "source",
            url = "https://example.org/book",
            title = "Основная серия. Побочная ветка 03. Название",
            seriesTitle = "Основная серия"
        )
        val parent = SourceSeries(
            sourceId = "source",
            url = "https://example.org/series",
            title = "Основная серия"
        )

        assertFalse(SeriesBookMembershipPolicy.belongsTo(parent, book))
        assertEquals("основная серия побочная ветка", SeriesBookMembershipPolicy.inferredNestedSeriesKey(parent, book))
        assertEquals(3, SeriesBookMembershipPolicy.inferredNestedVolumeNumber(parent, book))
    }

    @Test
    fun ordinarySubtitleWithoutNestedVolumeNumberIsNotDropped() {
        val book = SourceBook(
            sourceId = "source",
            url = "https://example.org/book",
            title = "Основная серия. Финал",
            seriesTitle = "Основная серия"
        )
        val parent = SourceSeries(
            sourceId = "source",
            url = "https://example.org/series",
            title = "Основная серия"
        )

        assertTrue(SeriesBookMembershipPolicy.belongsTo(parent, book))
        assertNull(SeriesBookMembershipPolicy.inferredNestedSeriesKey(parent, book))
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
