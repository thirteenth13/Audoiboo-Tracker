package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFormatPolicyTest {
    @Test fun acceptsCurrentAndLegacyEnvelopesWithValidTracker() {
        assertNull(BackupFormatPolicy.validate(BackupFormatPolicy.CURRENT_FORMAT, true, true))
        assertNull(BackupFormatPolicy.validate(1, true, true))
        assertNull(BackupFormatPolicy.validate(null, true, true))
    }

    @Test fun rejectsMissingOrMalformedTrackerBeforeRestore() {
        assertEquals("Backup is missing tracker data", BackupFormatPolicy.validate(12, false, false))
        assertEquals("Backup tracker data is invalid", BackupFormatPolicy.validate(12, true, false))
    }

    @Test fun rejectsFutureAndNegativeFormats() {
        assertEquals(
            "Backup format 13 is newer than supported format 12",
            BackupFormatPolicy.validate(13, true, true)
        )
        assertEquals("Backup format is invalid", BackupFormatPolicy.validate(-1, true, true))
    }

    @Test fun acceptsMissingOrValidDownloadsPayload() {
        assertNull(BackupFormatPolicy.validate(12, true, true, hasDownloads = false, downloadsAreValid = true))
        assertNull(BackupFormatPolicy.validate(12, true, true, hasDownloads = true, downloadsAreValid = true))
    }

    @Test fun rejectsMalformedDownloadsBeforeRestore() {
        assertEquals(
            "Backup downloads data is invalid",
            BackupFormatPolicy.validate(12, true, true, hasDownloads = true, downloadsAreValid = false)
        )
    }
}
