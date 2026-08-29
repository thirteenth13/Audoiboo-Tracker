package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadControlPolicyTest {
    @Test
    fun staleStartCannotReviveTerminalOrPausedStates() {
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.PAUSED))
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.CANCELLED))
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.COMPLETED))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.QUEUED))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.FAILED))
    }

    @Test
    fun pauseAndCancelArePersistableBeforeServiceSignal() {
        assertEquals(ManagedDownloadState.PAUSED, DownloadControlPolicy.pause(ManagedDownloadState.DOWNLOADING))
        assertEquals(ManagedDownloadState.CANCELLED, DownloadControlPolicy.cancel(ManagedDownloadState.DOWNLOADING))
        assertEquals(ManagedDownloadState.COMPLETED, DownloadControlPolicy.cancel(ManagedDownloadState.COMPLETED))
    }

    @Test
    fun resumeOnlyRequeuesPausedOrFailedTransfers() {
        assertEquals(ManagedDownloadState.QUEUED, DownloadControlPolicy.resume(ManagedDownloadState.PAUSED))
        assertEquals(ManagedDownloadState.QUEUED, DownloadControlPolicy.resume(ManagedDownloadState.FAILED))
        assertEquals(ManagedDownloadState.CANCELLED, DownloadControlPolicy.resume(ManagedDownloadState.CANCELLED))
        assertEquals(ManagedDownloadState.COMPLETED, DownloadControlPolicy.resume(ManagedDownloadState.COMPLETED))
    }
}
