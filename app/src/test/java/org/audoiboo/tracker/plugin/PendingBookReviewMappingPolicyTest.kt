package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingBookReviewMappingPolicyTest {
    @Test
    fun attachedMappingIsPreserved() {
        assertEquals(
            "canonical-book-7",
            PendingBookReviewMappingPolicy.canonicalBookId("canonical-book-7")
        )
    }

    @Test
    fun unlinkedObservationRemainsUnlinked() {
        assertNull(PendingBookReviewMappingPolicy.canonicalBookId(null))
    }
}
