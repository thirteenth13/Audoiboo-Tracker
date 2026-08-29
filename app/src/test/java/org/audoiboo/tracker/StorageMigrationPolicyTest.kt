package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageMigrationPolicyTest {
    @Test fun sameUriIsAlwaysAlreadyMigrated() {
        assertTrue(StorageMigrationPolicy.canReuseExisting("content://tree/file", "content://tree/file", null, 0))
    }

    @Test fun equalSizeDifferentUriIsStillACollision() {
        assertFalse(StorageMigrationPolicy.canReuseExisting("old", "new", 1234, 1234))
        assertFalse(StorageMigrationPolicy.canReuseExisting("old", "new", null, 1234))
        assertFalse(StorageMigrationPolicy.canReuseExisting("old", "new", 0, 0))
        assertFalse(StorageMigrationPolicy.canReuseExisting("old", "new", 1234, 1200))
    }

    @Test fun destinationRemovesOnlyLeadingDownloadSegment() {
        assertEquals("Audoiboo/Series", StorageMigrationPolicy.normalizedDestination("Download/Audoiboo/Series"))
        assertEquals("Audoiboo/Series", StorageMigrationPolicy.normalizedDestination("download\\Audoiboo\\Series"))
        assertEquals("MyDownload/Book", StorageMigrationPolicy.normalizedDestination("MyDownload/Book"))
        assertNull(StorageMigrationPolicy.normalizedDestination("Download/../Other"))
    }
}
