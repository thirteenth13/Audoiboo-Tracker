package org.audoiboo.tracker.plugin

/**
 * Keeps automatic rediscovery from undoing an explicit user decision for the same
 * canonical series/source observation.
 */
internal object SeriesDecisionPolicy {
    fun allowsAutomaticLink(
        canonicalSeriesId: String,
        decisions: List<SeriesMatchDecisionEntity>
    ): Boolean = decisions.none {
        it.canonicalSeriesId == canonicalSeriesId && it.decision == "USER_REJECTED"
    }
}
