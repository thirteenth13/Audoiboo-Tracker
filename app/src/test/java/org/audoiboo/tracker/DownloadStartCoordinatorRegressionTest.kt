package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStartCoordinatorRegressionTest {
    @Test fun pausedQueuedItemIsRemovedBeforePromotion() {
        val coordinator = DownloadStartCoordinator(1)
        assertTrue(coordinator.request("active"))
        assertFalse(coordinator.request("paused"))
        assertTrue(coordinator.cancelQueued("paused"))
        assertEquals(null, coordinator.finished("active"))
        assertEquals(0, coordinator.activeCount())
        assertEquals(0, coordinator.queuedCount())
    }

    @Test fun onlyOneQueuedItemIsPromotedPerFinishedTransfer() {
        val coordinator = DownloadStartCoordinator(1)
        assertTrue(coordinator.request("a"))
        assertFalse(coordinator.request("b"))
        assertFalse(coordinator.request("c"))
        assertEquals("b", coordinator.finished("a"))
        assertEquals(1, coordinator.activeCount())
        assertEquals(1, coordinator.queuedCount())
    }
}
