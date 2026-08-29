package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedDownloadMigrationPolicyTest {
    @Test fun keepsLegacyWhenRoomEmptyAndLegacyMalformed() {
        assertFalse(ManagedDownloadMigrationPolicy.shouldRemoveLegacy(roomCount = 0, legacyParsed = false))
    }

    @Test fun removesLegacyAfterSuccessfulParseIntoEmptyRoom() {
        assertTrue(ManagedDownloadMigrationPolicy.shouldRemoveLegacy(roomCount = 0, legacyParsed = true))
    }

    @Test fun removesLegacyWhenRoomAlreadyOwnsState() {
        assertTrue(ManagedDownloadMigrationPolicy.shouldRemoveLegacy(roomCount = 1, legacyParsed = false))
    }
}
