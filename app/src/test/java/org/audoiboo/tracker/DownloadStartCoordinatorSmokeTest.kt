package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStartCoordinatorSmokeTest {
    @Test fun queuePromotionPreservesLimit() {
        val coordinator = DownloadStartCoordinator(2)
        assertTrue(coordinator.request("a"))
        assertTrue(coordinator.request("b"))
        assertFalse(coordinator.request("c"))
        assertEquals("c", coordinator.finished("a"))
        assertEquals(2, coordinator.activeCount())
        assertEquals(0, coordinator.queuedCount())
    }
}
