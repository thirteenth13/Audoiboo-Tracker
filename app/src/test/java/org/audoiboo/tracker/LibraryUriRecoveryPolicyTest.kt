package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryUriRecoveryPolicyTest {
    @Test fun safPathStripsDownloadRoot() {
        assertEquals("Audoiboo/Series/Book", LibraryUriRecoveryPolicy.safRelativeDir("Download/Audoiboo/Series/Book"))
    }

    @Test fun mediaStoreCandidatesCoverLegacyAndSafPaths() {
        val paths = LibraryUriRecoveryPolicy.mediaStoreRelativeDirs("Audoiboo/Series/Book")
        assertTrue("Audoiboo/Series/Book" in paths)
        assertTrue("Download/Audoiboo/Series/Book" in paths)
    }

    @Test fun traversalPathIsRejected() {
        assertEquals(null, LibraryUriRecoveryPolicy.safRelativeDir("Download/../Other"))
        assertTrue(LibraryUriRecoveryPolicy.mediaStoreRelativeDirs("Download/../Other").isEmpty())
    }
}
