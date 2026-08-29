package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadControlPolicyRegressionTest {
    @Test fun staleStartCannotReviveTerminalOrPausedStates() {
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.PAUSED))
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.CANCELLED))
        assertFalse(DownloadControlPolicy.canStart(ManagedDownloadState.COMPLETED))
    }

    @Test fun recoverableStatesRemainStartable() {
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.QUEUED))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.FAILED))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.DOWNLOADING))
        assertTrue(DownloadControlPolicy.canStart(ManagedDownloadState.EXTRACTING))
    }
}
