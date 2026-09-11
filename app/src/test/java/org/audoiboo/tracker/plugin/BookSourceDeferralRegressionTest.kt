package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookSourceDeferralRegressionTest {
    @Test
    fun unnumberedConflictingAuthorIsReviewInsteadOfAutomaticAttachment() {
        val incoming = SourceBook(
            sourceId = "provider",
            url = "https://provider.test/prometey",
            title = "Прометей",
            authors = listOf(SourceAuthor("Другой Автор")),
            seriesTitle = "Стеллар"
        )
        val canonical = listOf(
            CanonicalBookMatchInput("prometey", "Прометей", listOf("Роман Прокофьев"), 9.0)
        )

        val match = SourceIdentityMatcher.bestBookMatch(incoming, canonical)

        assertEquals(MatchDisposition.REVIEW, match?.disposition)
        assertEquals("prometey", match?.value?.id)
    }

    @Test
    fun occupiedVolumeCannotBecomeASecondCanonicalBookWhenMatchIsWeak() {
        val incoming = SourceBook(
            sourceId = "provider",
            url = "https://provider.test/volume-9",
            title = "Слабое название",
            seriesTitle = "Стеллар",
            seriesNumber = 9.0
        )
        val canonical = listOf(
            CanonicalBookMatchInput("prometey", "Прометей", listOf("Роман Прокофьев"), 9.0)
        )

        assertFalse(CanonicalBookCreationPolicy.shouldCreateUnmatched(incoming, canonical))
    }
}
