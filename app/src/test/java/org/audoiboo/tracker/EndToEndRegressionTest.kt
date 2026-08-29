package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cross-package regression coverage for the main download -> storage -> player -> backup path. */
class EndToEndRegressionTest {
    @Test fun resumedDownloadCanFlowIntoSafAndPlayerWithoutUnsafeShortcuts() {
        val existing = 1_000L
        assertTrue(DownloadResumePolicy.canAppend(existing, 206, "bytes 1000-1999/2000"))
        assertEquals(2_000L, DownloadResumePolicy.expectedTotal(existing, 1_000L, "bytes 1000-1999/2000"))
        assertFalse(DownloadStagingPolicy.isComplete(existing, 2_000L))
        assertTrue(DownloadStagingPolicy.isComplete(2_000L, 2_000L))

        val entry = ArchiveEntryPolicy.safeRelativePath("Disc 1/001.mp3")
        assertEquals("Disc 1/001.mp3", entry)
        assertEquals("Audoiboo/Series/Book", StorageMigrationPolicy.normalizedDestination("Download/Audoiboo/Series/Book"))
        assertTrue(LibraryUriRecoveryPolicy.mediaStoreRelativeDirs("Download/Audoiboo/Series/Book").contains("Download/Audoiboo/Series/Book"))

        val transition = PlayerLogic.automaticTransition(
            trackCount = 4,
            currentIndex = 1,
            brokenIndices = setOf(1, 2),
            sleepAtEndOfTrack = false
        )
        assertEquals(3, transition.targetIndex)
        assertTrue(transition.shouldPlay)

        val positions = TrackPositionSnapshotPolicy.merge(
            room = mapOf("content://track" to 10_000L),
            cached = mapOf("content://track" to 15_000L),
            pending = mapOf("content://track" to 20_000L)
        )
        assertEquals(20_000L, positions["content://track"])

        assertNull(
            BackupFormatPolicy.validate(
                format = BackupFormatPolicy.CURRENT_FORMAT,
                hasTracker = true,
                trackerIsValidArray = true,
                hasDownloads = true,
                downloadsAreValid = true,
                sectionsAreValid = true
            )
        )
    }

    @Test fun unsafeOrAmbiguousStateFailsClosedAcrossSubsystems() {
        assertNull(ArchiveEntryPolicy.safeRelativePath("../escape.mp3"))
        assertNull(StorageMigrationPolicy.normalizedDestination("Download/../escape"))
        assertFalse(StorageMigrationPolicy.canReuseExisting("content://old", "content://new", 4096, 4096))
        assertEquals(0L, DownloadStagingPolicy.actualProgress(5_000L, 4_000L))

        val sleep = PlayerLogic.automaticTransition(
            trackCount = 3,
            currentIndex = 1,
            brokenIndices = setOf(1, 2),
            sleepAtEndOfTrack = true
        )
        assertNull(sleep.targetIndex)
        assertFalse(sleep.shouldPlay)
        assertTrue(sleep.consumeSleepAtEnd)

        assertTrue(
            BackupFormatPolicy.validate(
                format = BackupFormatPolicy.CURRENT_FORMAT + 1,
                hasTracker = true,
                trackerIsValidArray = true
            ) != null
        )
    }
}
