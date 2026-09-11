package org.audoiboo.tracker.plugin

/**
 * Prevents a weak provider observation from manufacturing a second canonical book for an
 * already-occupied volume. A source with a new/unmapped volume may still extend the series.
 * Identity matching remains responsible for deciding whether an observation can attach to the
 * existing canonical volume; this policy never links by ordinal alone.
 */
object CanonicalBookCreationPolicy {
    fun shouldCreateUnmatched(
        incoming: SourceBook,
        candidates: List<CanonicalBookMatchInput>
    ): Boolean {
        val incomingNumber = incoming.seriesNumber?.takeIf(::isWholePositiveVolume) ?: return true
        return candidates.none { candidate ->
            candidate.number?.takeIf(::isWholePositiveVolume)?.let { sameVolume(it, incomingNumber) } == true
        }
    }

    private fun isWholePositiveVolume(value: Double): Boolean =
        value >= 1.0 && value % 1.0 == 0.0

    private fun sameVolume(left: Double, right: Double): Boolean = left.toLong() == right.toLong()
}
