package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStateRegressionTest {
    @Test fun pausedAndCancelledRemainStableControlResults() {
        assertEquals(ManagedDownloadState.PAUSED, DownloadControlPolicy.pause(ManagedDownloadState.PAUSED))
        assertEquals(ManagedDownloadState.CANCELLED, DownloadControlPolicy.cancel(ManagedDownloadState.CANCELLED))
        assertEquals(ManagedDownloadState.COMPLETED, DownloadControlPolicy.cancel(ManagedDownloadState.COMPLETED))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.QUEUED))
    }
}
