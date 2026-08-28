package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerQueueActionsTest {
    @Test fun insertsAfterLastCurrentSeriesBook() {
        val queue = listOf("s1-b1", "s1-b2", "other")
        val result = PlayerQueueActions.afterSeries(queue, "s1-b1", listOf("s1-b1", "s1-b2", "s1-b3"), "new")
        assertEquals(listOf("s1-b1", "s1-b2", "new", "other"), result)
    }

    @Test fun fallsBackAfterActiveBookWhenSeriesNotQueued() {
        val queue = listOf("active", "other")
        val result = PlayerQueueActions.afterSeries(queue, "active", listOf("series-a", "series-b"), "new")
        assertEquals(listOf("active", "new", "other"), result)
    }

    @Test fun removesExistingTargetBeforeReinserting() {
        val queue = listOf("active", "new", "series-b", "other")
        val result = PlayerQueueActions.afterSeries(queue, "active", listOf("active", "series-b"), "new")
        assertEquals(listOf("active", "series-b", "new", "other"), result)
    }
}
