package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStagingPolicyTest {
    @Test fun actualPartLengthOverridesPersistedProgress() {
        assertEquals(40L, DownloadStagingPolicy.actualProgress(40L, 100L))
        assertEquals(0L, DownloadStagingPolicy.actualProgress(0L, 100L))
    }

    @Test fun oversizedPartIsDiscarded() {
        assertTrue(DownloadStagingPolicy.shouldDiscard(101L, 100L))
        assertEquals(0L, DownloadStagingPolicy.actualProgress(101L, 100L))
        assertFalse(DownloadStagingPolicy.shouldDiscard(50L, 100L))
    }

    @Test fun exactKnownTotalCanFinalizeWithoutNetwork() {
        assertTrue(DownloadStagingPolicy.isComplete(100L, 100L))
        assertFalse(DownloadStagingPolicy.isComplete(99L, 100L))
        assertFalse(DownloadStagingPolicy.isComplete(100L, -1L))
        assertFalse(DownloadStagingPolicy.isComplete(0L, 0L))
    }
}
