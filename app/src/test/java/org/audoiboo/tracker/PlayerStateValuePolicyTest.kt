package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStateValuePolicyTest {
    @Test fun speedsMustBeFiniteNumbersWithinPlayerRange() {
        assertEquals(1.25f, PlayerStateValuePolicy.speed(1.25), .0001f)
        assertNull(PlayerStateValuePolicy.speed("1.25"))
        assertNull(PlayerStateValuePolicy.speed(Double.NaN))
        assertNull(PlayerStateValuePolicy.speed(Double.POSITIVE_INFINITY))
        assertNull(PlayerStateValuePolicy.speed(.49))
        assertNull(PlayerStateValuePolicy.speed(3.01))
    }

    @Test fun timestampsRejectStringsFractionsAndNegativeValues() {
        assertEquals(123L, PlayerStateValuePolicy.timestamp(123L))
        assertNull(PlayerStateValuePolicy.timestamp("123"))
        assertNull(PlayerStateValuePolicy.timestamp(1.5))
        assertNull(PlayerStateValuePolicy.timestamp(-1L))
    }

    @Test fun textAndKeysRequireExpectedTypes() {
        assertTrue(PlayerStateValuePolicy.validKey("series"))
        assertNull(PlayerStateValuePolicy.text(42))
        assertNull(PlayerStateValuePolicy.text("", allowBlank = false))
        assertEquals("", PlayerStateValuePolicy.text("", allowBlank = true))
    }
}
