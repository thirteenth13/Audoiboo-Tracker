package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesDecisionPolicyTest {
    @Test
    fun rejectedDecisionForSameCanonicalSeriesBlocksAutomaticRelink() {
        val decisions = listOf(
            SeriesMatchDecisionEntity(
                canonicalSeriesId = "series-a",
                sourceId = "baza-knig",
                remoteKey = "remote-1",
                decision = "USER_REJECTED"
            )
        )

        assertFalse(SeriesDecisionPolicy.allowsAutomaticLink("series-a", decisions))
    }

    @Test
    fun rejectionForAnotherCanonicalSeriesDoesNotBlock() {
        val decisions = listOf(
            SeriesMatchDecisionEntity(
                canonicalSeriesId = "series-b",
                sourceId = "baza-knig",
                remoteKey = "remote-1",
                decision = "USER_REJECTED"
            )
        )

        assertTrue(SeriesDecisionPolicy.allowsAutomaticLink("series-a", decisions))
    }

    @Test
    fun acceptedAndAutomaticDecisionsRemainEligible() {
        val decisions = listOf(
            SeriesMatchDecisionEntity(
                canonicalSeriesId = "series-a",
                sourceId = "baza-knig",
                remoteKey = "remote-1",
                decision = "USER_ACCEPTED"
            ),
            SeriesMatchDecisionEntity(
                canonicalSeriesId = "series-a",
                sourceId = "booktracker",
                remoteKey = "remote-2",
                decision = "AUTO_ACCEPTED"
            )
        )

        assertTrue(SeriesDecisionPolicy.allowsAutomaticLink("series-a", decisions))
    }
}
