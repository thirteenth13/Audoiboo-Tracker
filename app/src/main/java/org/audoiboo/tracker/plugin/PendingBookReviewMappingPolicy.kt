package org.audoiboo.tracker.plugin

/**
 * REVIEW_PENDING is metadata about an uncertain match, not an instruction to detach a source book
 * that was already linked earlier. Keep any existing canonical mapping intact while refreshing the
 * observation; genuinely unlinked observations remain unlinked.
 */
internal object PendingBookReviewMappingPolicy {
    fun canonicalBookId(existingCanonicalBookId: String?): String? = existingCanonicalBookId
}
