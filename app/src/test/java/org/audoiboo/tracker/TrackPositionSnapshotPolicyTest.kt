package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TrackPositionSnapshotPolicyTest {
    @Test fun newestCachedAndPendingValuesOverrideRoom() {
        val merged = TrackPositionSnapshotPolicy.merge(
            room = mapOf("a" to 100L, "b" to 200L),
            cached = mapOf("a" to 150L, "c" to 300L),
            pending = mapOf("a" to 175L, "b" to 250L)
        )
        assertEquals(175L, merged["a"])
        assertEquals(250L, merged["b"])
        assertEquals(300L, merged["c"])
    }

    @Test fun invalidKeysAreDroppedAndNegativeValuesAreClamped() {
        val merged = TrackPositionSnapshotPolicy.merge(
            room = mapOf("" to 99L, "a" to -5L),
            cached = emptyMap(),
            pending = emptyMap()
        )
        assertFalse(merged.containsKey(""))
        assertEquals(0L, merged["a"])
    }
}
