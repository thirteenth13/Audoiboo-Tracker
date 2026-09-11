package org.audoiboo.tracker

import org.audoiboo.tracker.plugin.CanonicalBookMatchInput
import org.audoiboo.tracker.plugin.audiobooAuthorPageBookMatch
import org.audoiboo.tracker.plugin.audiobooComparableBookTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudiobooAuthorPageMatchingTest {
    @Test
    fun removesSeriesAndVolumeDecorations() {
        val book = FastBook(
            title = "Древний 1. Катастрофа. Аудиокнига",
            url = "https://audioboo.org/example",
            author = "Тармашев Сергей",
            coverUrl = null,
            seriesTitle = "Древний"
        )
        val candidates = listOf(
            CanonicalBookMatchInput("1", "Катастрофа", listOf("Сергей Тармашев"), 1.0),
            CanonicalBookMatchInput("2", "Корпорация", listOf("Сергей Тармашев"), 2.0)
        )

        assertEquals("катастрофа", audiobooComparableBookTitle(book.title, book.seriesTitle))
        assertEquals("1", audiobooAuthorPageBookMatch(book, candidates)?.id)
    }

    @Test
    fun doesNotRelaxAmbiguousSingleWordTitles() {
        val book = FastBook(
            title = "Рассвет Тьмы",
            url = "https://audioboo.org/example",
            author = "Тармашев Сергей",
            coverUrl = null,
            seriesTitle = null
        )
        val candidates = listOf(
            CanonicalBookMatchInput("broad", "Тьма"),
            CanonicalBookMatchInput("other", "Закат Тьмы")
        )

        assertNull(audiobooAuthorPageBookMatch(book, candidates))
    }
}
