package org.audoiboo.tracker

import org.junit.Assert.*
import org.junit.Test

class DownloadStartCoordinatorTest {
    @Test fun capsActiveTransfersAndQueuesTheRest() {
        val c = DownloadStartCoordinator(2)
        assertTrue(c.request("a"))
        assertTrue(c.request("b"))
        assertFalse(c.request("c"))
        assertEquals(2, c.activeCount())
        assertEquals(1, c.queuedCount())
        assertTrue(c.isQueued("c"))
    }

    @Test fun finishingPromotesNextWithoutExceedingLimit() {
        val c = DownloadStartCoordinator(2)
        c.request("a"); c.request("b"); c.request("c")
        assertEquals("c", c.finished("a"))
        assertTrue(c.isActive("c"))
        assertEquals(2, c.activeCount())
        assertEquals(0, c.queuedCount())
    }

    @Test fun duplicateRequestNeverCreatesDuplicateQueueEntry() {
        val c = DownloadStartCoordinator(1)
        assertTrue(c.request("a"))
        assertFalse(c.request("b"))
        assertFalse(c.request("b"))
        assertEquals(1, c.queuedCount())
    }

    @Test fun pausedOrCancelledQueuedDownloadCanBeRemovedBeforePromotion() {
        val c = DownloadStartCoordinator(1)
        c.request("a"); c.request("b")
        assertTrue(c.cancelQueued("b"))
        assertNull(c.finished("a"))
        assertFalse(c.isActive("b"))
    }

    @Test fun zeroConfiguredConcurrencyStillAllowsOne() {
        val c = DownloadStartCoordinator(0)
        assertTrue(c.request("a"))
        assertFalse(c.request("b"))
    }
}
