package org.audoiboo.tracker

import org.audoiboo.tracker.plugin.BookSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceObservationPreferenceTest {
    @Test
    fun higherConfidenceWinsEvenWhenOlder() {
        val result = SourceObservationPreference.rank(
            listOf(
                source("fresh-low", confidence = 0.72f, lastCheckedAt = 500L, lastSeenAt = 500L),
                source("old-high", confidence = 0.96f, lastCheckedAt = 100L, lastSeenAt = 100L)
            )
        )

        assertEquals("old-high", result.first().sourceId)
    }

    @Test
    fun newerCheckBreaksEqualConfidenceTie() {
        val result = SourceObservationPreference.rank(
            listOf(
                source("older", confidence = 0.9f, lastCheckedAt = 100L, lastSeenAt = 300L),
                source("newer", confidence = 0.9f, lastCheckedAt = 400L, lastSeenAt = 400L)
            )
        )

        assertEquals("newer", result.first().sourceId)
    }

    @Test
    fun equalMetadataHasDeterministicSourceIdFallback() {
        val result = SourceObservationPreference.rank(
            listOf(
                source("zeta", confidence = 0.9f, lastCheckedAt = 100L, lastSeenAt = 100L),
                source("alpha", confidence = 0.9f, lastCheckedAt = 100L, lastSeenAt = 100L)
            )
        )

        assertEquals(listOf("alpha", "zeta"), result.map { it.sourceId })
    }

    private fun source(
        sourceId: String,
        confidence: Float,
        lastCheckedAt: Long?,
        lastSeenAt: Long
    ) = BookSourceEntity(
        key = "$sourceId::book",
        canonicalBookId = "book",
        canonicalSeriesId = "series",
        sourceId = sourceId,
        remoteKey = "book",
        url = "https://$sourceId.test/book",
        remoteTitle = "Book",
        confidence = confidence,
        firstSeenAt = 1L,
        lastSeenAt = lastSeenAt,
        lastCheckedAt = lastCheckedAt
    )
}
