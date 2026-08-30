package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceMetadataMergePolicyTest {
    @Test
    fun newUnverifiedSnapshotStaysUnverified() {
        assertFalse(SourceMetadataMergePolicy.userVerified(existing = null, incoming = false))
    }

    @Test
    fun explicitVerificationUpgradesExistingSnapshot() {
        assertTrue(SourceMetadataMergePolicy.userVerified(existing = false, incoming = true))
    }

    @Test
    fun verifiedSnapshotCannotBeDowngradedByRediscovery() {
        assertTrue(SourceMetadataMergePolicy.userVerified(existing = true, incoming = false))
    }
}
