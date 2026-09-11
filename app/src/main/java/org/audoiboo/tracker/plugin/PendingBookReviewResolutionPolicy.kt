package org.audoiboo.tracker.plugin

internal data class PendingBookReviewResolution(
    val canonicalBookId: String?,
    val decision: String,
    val canResolve: Boolean
)

/** Pure policy for manual pending-book review actions. */
internal object PendingBookReviewResolutionPolicy {
    fun resolve(
        existingCanonicalBookId: String?,
        candidateCanonicalBookId: String?,
        accept: Boolean
    ): PendingBookReviewResolution {
        if (!accept) {
            return PendingBookReviewResolution(
                canonicalBookId = existingCanonicalBookId,
                decision = "USER_REJECTED",
                canResolve = true
            )
        }

        val target = candidateCanonicalBookId?.takeIf { it.isNotBlank() }
            ?: return PendingBookReviewResolution(
                canonicalBookId = existingCanonicalBookId,
                decision = "REVIEW_PENDING",
                canResolve = false
            )

        return PendingBookReviewResolution(
            canonicalBookId = target,
            decision = "USER_ACCEPTED",
            canResolve = true
        )
    }
}
