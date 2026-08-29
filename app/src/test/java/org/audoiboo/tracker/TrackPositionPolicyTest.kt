package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPositionPolicyTest {
    @Test fun acceptsNonNegativeNumericPositions() {
        assertEquals(0L, TrackPositionPolicy.normalize(0))
        assertEquals(123L, TrackPositionPolicy.normalize(123L))
        assertEquals(12L, TrackPositionPolicy.normalize(12.9))
    }

    @Test fun rejectsNegativeNonFiniteAndTextValues() {
        assertNull(TrackPositionPolicy.normalize(-1L))
        assertNull(TrackPositionPolicy.normalize(Double.NaN))
        assertNull(TrackPositionPolicy.normalize(Double.POSITIVE_INFINITY))
        assertNull(TrackPositionPolicy.normalize("123"))
        assertNull(TrackPositionPolicy.normalize(null))
    }

    @Test fun validatesPersistedUriKeys() {
        assertTrue(TrackPositionPolicy.validKey("content://media/external/audio/1"))
        assertFalse(TrackPositionPolicy.validKey(""))
        assertFalse(TrackPositionPolicy.validKey("x".repeat(8193)))
    }
}
