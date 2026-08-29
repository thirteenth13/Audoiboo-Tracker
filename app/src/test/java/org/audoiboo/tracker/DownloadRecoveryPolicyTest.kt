package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRecoveryPolicyTest {
    @Test fun runtimeStatesReturnToQueuedAfterProcessDeath() {
        assertEquals(ManagedDownloadState.QUEUED, DownloadRecoveryPolicy.normalizedState(ManagedDownloadState.DOWNLOADING))
        assertEquals(ManagedDownloadState.QUEUED, DownloadRecoveryPolicy.normalizedState(ManagedDownloadState.EXTRACTING))
        assertEquals(ManagedDownloadState.QUEUED, DownloadRecoveryPolicy.normalizedState(ManagedDownloadState.QUEUED))
    }

    @Test fun onlyUnfinishedAutomaticStatesAreRecovered() {
        assertTrue(DownloadRecoveryPolicy.shouldRecover(ManagedDownloadState.QUEUED))
        assertTrue(DownloadRecoveryPolicy.shouldRecover(ManagedDownloadState.DOWNLOADING))
        assertTrue(DownloadRecoveryPolicy.shouldRecover(ManagedDownloadState.EXTRACTING))
        assertFalse(DownloadRecoveryPolicy.shouldRecover(ManagedDownloadState.PAUSED))
        assertFalse(DownloadRecoveryPolicy.shouldRecover(ManagedDownloadState.CANCELLED))
        assertFalse(DownloadRecoveryPolicy.shouldRecover(ManagedDownloadState.COMPLETED))
        assertFalse(DownloadRecoveryPolicy.shouldRecover(ManagedDownloadState.FAILED))
    }

    @Test fun workerUsesSameStartPolicyAsForegroundService() {
        ManagedDownloadState.entries.forEach { state ->
            assertEquals(DownloadControlPolicy.canStart(state), DownloadRecoveryPolicy.workerCanKick(state))
        }
    }
}
