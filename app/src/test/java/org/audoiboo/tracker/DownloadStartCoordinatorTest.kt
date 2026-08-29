package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStartCoordinatorTest {
    @Test fun startsUpToLimitAndQueuesTheRest() {
        val coordinator = DownloadStartCoordinator(2)
        assertTrue(coordinator.request("a"))
        assertTrue(coordinator.request("b"))
        assertFalse(coordinator.request("c"))
        assertEquals(2, coordinator.activeCount())
        assertEquals(1, coordinator.queuedCount())
    }

    @Test fun finishingPromotesQueuedInFifoOrder() {
        val coordinator = DownloadStartCoordinator(1)
        assertTrue(coordinator.request("a"))
        assertFalse(coordinator.request("b"))
        assertFalse(coordinator.request("c"))
        assertEquals("b", coordinator.finished("a"))
        assertEquals("c", coordinator.finished("b"))
        assertEquals(null, coordinator.finished("c"))
    }

    @Test fun duplicateIdIsNeitherStartedNorQueuedTwice() {
        val coordinator = DownloadStartCoordinator(1)
        assertTrue(coordinator.request("a"))
        assertFalse(coordinator.request("a"))
        assertFalse(coordinator.request("b"))
        assertFalse(coordinator.request("b"))
        assertEquals(1, coordinator.activeCount())
        assertEquals(1, coordinator.queuedCount())
    }

    @Test fun queuedItemCanBeCancelled() {
        val coordinator = DownloadStartCoordinator(1)
        assertTrue(coordinator.request("a"))
        assertFalse(coordinator.request("b"))
        assertTrue(coordinator.cancelQueued("b"))
        assertFalse(coordinator.isQueued("b"))
        assertEquals(null, coordinator.finished("a"))
    }
}
