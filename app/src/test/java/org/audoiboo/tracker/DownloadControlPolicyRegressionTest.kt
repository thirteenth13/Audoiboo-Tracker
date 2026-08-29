package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadControlPolicyRegressionTest {
    @Test fun staleStartCannotReviveTerminalPausedOrFailedStates() {
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.PAUSED))
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.CANCELLED))
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.COMPLETED))
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.FAILED))
    }

    @Test fun automaticRecoverableStatesRemainStartable() {
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.QUEUED))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.DOWNLOADING))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.EXTRACTING))
    }

    @Test fun explicitRetryMayStillReviveFailedTransfer() {
        assertTrue(DownloadControlPolicy.canManualStart(ManagedDownloadState.FAILED))
        assertTrue(DownloadControlPolicy.resume(ManagedDownloadState.FAILED) == ManagedDownloadState.QUEUED)
    }
}
