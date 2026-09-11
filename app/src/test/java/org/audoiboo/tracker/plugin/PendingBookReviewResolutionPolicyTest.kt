package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingBookReviewResolutionPolicyTest {
    @Test
    fun acceptMapsObservationToCandidateAndMarksUserAccepted() {
        val result = PendingBookReviewResolutionPolicy.resolve(
            existingCanonicalBookId = null,
            candidateCanonicalBookId = "canonical-book-2",
            accept = true
        )

        assertTrue(result.canResolve)
        assertEquals("canonical-book-2", result.canonicalBookId)
        assertEquals("USER_ACCEPTED", result.decision)
    }

    @Test
    fun rejectKeepsObservationUnlinkedAndMarksUserRejected() {
        val result = PendingBookReviewResolutionPolicy.resolve(
            existingCanonicalBookId = null,
            candidateCanonicalBookId = "canonical-book-2",
            accept = false
        )

        assertTrue(result.canResolve)
        assertNull(result.canonicalBookId)
        assertEquals("USER_REJECTED", result.decision)
    }

    @Test
    fun rejectNeverDetachesAnExistingMapping() {
        val result = PendingBookReviewResolutionPolicy.resolve(
            existingCanonicalBookId = "canonical-book-1",
            candidateCanonicalBookId = "canonical-book-2",
            accept = false
        )

        assertTrue(result.canResolve)
        assertEquals("canonical-book-1", result.canonicalBookId)
        assertEquals("USER_REJECTED", result.decision)
    }

    @Test
    fun acceptWithoutCandidateCannotResolveOrOverwriteState() {
        val result = PendingBookReviewResolutionPolicy.resolve(
            existingCanonicalBookId = "canonical-book-1",
            candidateCanonicalBookId = null,
            accept = true
        )

        assertFalse(result.canResolve)
        assertEquals("canonical-book-1", result.canonicalBookId)
        assertEquals("REVIEW_PENDING", result.decision)
    }
}
