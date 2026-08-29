package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSafetyPolicyTest {
    @Test fun unknownOrReasonableEntrySizeIsAllowed() {
        assertTrue(ArchiveSafetyPolicy.validateDeclaredEntrySize(-1))
        assertTrue(ArchiveSafetyPolicy.validateDeclaredEntrySize(1024))
        assertTrue(ArchiveSafetyPolicy.validateDeclaredEntrySize(ArchiveSafetyPolicy.MAX_SINGLE_FILE_BYTES))
    }

    @Test fun oversizedEntryIsRejected() {
        assertFalse(ArchiveSafetyPolicy.validateDeclaredEntrySize(ArchiveSafetyPolicy.MAX_SINGLE_FILE_BYTES + 1))
    }

    @Test fun reasonableArchiveTotalsAreAllowed() {
        assertTrue(ArchiveSafetyPolicy.validateTotals(100, 1024L * 1024 * 1024))
    }

    @Test fun excessiveFileCountIsRejected() {
        assertFalse(ArchiveSafetyPolicy.validateTotals(ArchiveSafetyPolicy.MAX_FILES + 1, 0))
    }

    @Test fun excessiveExpandedSizeIsRejected() {
        assertFalse(ArchiveSafetyPolicy.validateTotals(1, ArchiveSafetyPolicy.MAX_UNCOMPRESSED_BYTES + 1))
    }
}
