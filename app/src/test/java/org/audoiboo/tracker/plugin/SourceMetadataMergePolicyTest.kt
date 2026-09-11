package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
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

    @Test
    fun providerSnapshotsUseIndependentKeysAndAccumulateOnCanonicalBooks() {
        val rows = linkedMapOf<String, Pair<String, String>>()

        fun applySnapshot(sourceId: String, canonicalBookIds: List<String>) {
            canonicalBookIds.forEach { canonicalBookId ->
                val remoteKey = "https://$sourceId.example/$canonicalBookId"
                val key = SourceKeys.bookSourceKey(sourceId, SourceKeys.remoteKey(null, remoteKey))
                rows[key] = canonicalBookId to sourceId
            }
        }

        applySnapshot("provider-a", listOf("book-1", "book-2"))
        applySnapshot("provider-b", listOf("book-2", "book-3"))

        val sourcesByBook = rows.values
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, sources) -> sources.toSet() }

        assertEquals(setOf("provider-a"), sourcesByBook["book-1"])
        assertEquals(setOf("provider-a", "provider-b"), sourcesByBook["book-2"])
        assertEquals(setOf("provider-b"), sourcesByBook["book-3"])
        assertEquals(4, rows.size)
    }
}
