package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadStorePolicyTest {
    @Test fun terminalStatesRemainTerminalAcrossPersistence() {
        listOf(ManagedDownloadState.COMPLETED, ManagedDownloadState.CANCELLED, ManagedDownloadState.PAUSED).forEach { state ->
            val record = ManagedDownloadRecord("id-$state", "Book", "Series", null, "book", "archive", "dir", "book", "book.zip", state)
            assertEquals(state, record.toEntity().toRecord().state)
            assertFalse(DownloadRecoveryPolicy.shouldRecover(state))
        }
    }

    @Test fun recoverableStatesSurviveRoomRoundTripThenNormalize() {
        listOf(ManagedDownloadState.DOWNLOADING, ManagedDownloadState.EXTRACTING).forEach { state ->
            val record = ManagedDownloadRecord("id-$state", "Book", "Series", null, "book", "archive", "dir", "book", "book.zip", state)
            val restored = record.toEntity().toRecord()
            assertEquals(state, restored.state)
            assertEquals(ManagedDownloadState.QUEUED, DownloadRecoveryPolicy.normalizedState(restored.state))
            assertTrue(DownloadRecoveryPolicy.shouldRecover(restored.state))
        }
    }
}
