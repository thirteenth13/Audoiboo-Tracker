package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalBookCreationPolicyTest {
    private val canonical = listOf(
        CanonicalBookMatchInput("one", "Инкарнатор", listOf("Роман Прокофьев"), 1.0),
        CanonicalBookMatchInput("two", "Трибут", listOf("Роман Прокофьев"), 2.0)
    )

    @Test
    fun weakObservationCannotCreateSecondBookForOccupiedVolume() {
        val incoming = SourceBook(
            sourceId = "provider",
            url = "https://provider.test/book-2",
            title = "Нечеткое название",
            seriesTitle = "Стеллар",
            seriesNumber = 2.0
        )
        assertFalse(CanonicalBookCreationPolicy.shouldCreateUnmatched(incoming, canonical))
    }

    @Test
    fun genuinelyNewVolumeCanExtendCanonicalSeries() {
        val incoming = SourceBook(
            sourceId = "provider",
            url = "https://provider.test/book-3",
            title = "Новая книга",
            seriesTitle = "Стеллар",
            seriesNumber = 3.0
        )
        assertTrue(CanonicalBookCreationPolicy.shouldCreateUnmatched(incoming, canonical))
    }

    @Test
    fun missingOrFractionalOrdinalDoesNotBlockCreationByItself() {
        val missing = SourceBook("provider", url = "https://provider.test/new", title = "Новая книга")
        val fractional = missing.copy(url = "https://provider.test/novella", seriesNumber = 2.5)
        assertTrue(CanonicalBookCreationPolicy.shouldCreateUnmatched(missing, canonical))
        assertTrue(CanonicalBookCreationPolicy.shouldCreateUnmatched(fractional, canonical))
    }
}
