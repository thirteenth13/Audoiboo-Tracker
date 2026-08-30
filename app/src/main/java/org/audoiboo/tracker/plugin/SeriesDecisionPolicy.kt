package org.audoiboo.tracker.plugin

/**
 * Keeps automatic rediscovery from undoing explicit user decisions and prevents
 * the same ambiguous discovery from being queued over and over again.
 */
internal object SeriesDecisionPolicy {
    fun allowsAutomaticLink(
        canonicalSeriesId: String,
        decisions: List<SeriesMatchDecisionEntity>
    ): Boolean = decisions.none {
        it.canonicalSeriesId == canonicalSeriesId && it.decision == "USER_REJECTED"
    }

    fun shouldQueueReview(
        canonicalSeriesId: String,
        decisions: List<SeriesMatchDecisionEntity>
    ): Boolean = decisions.none {
        it.canonicalSeriesId == canonicalSeriesId && it.decision in setOf(
            "REVIEW_PENDING",
            "USER_ACCEPTED",
            "USER_REJECTED",
            "AUTO_ACCEPTED"
        )
    }
}
