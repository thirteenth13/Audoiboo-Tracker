package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerExtrasValuePolicyTest {
    @Test fun nonNegativeLongRejectsStringsFractionsAndNegativeValues() {
        assertEquals(42L, PlayerExtrasValuePolicy.nonNegativeLong(42L))
        assertNull(PlayerExtrasValuePolicy.nonNegativeLong("42"))
        assertNull(PlayerExtrasValuePolicy.nonNegativeLong(1.5))
        assertNull(PlayerExtrasValuePolicy.nonNegativeLong(-1L))
        assertNull(PlayerExtrasValuePolicy.nonNegativeLong(Double.NaN))
    }

    @Test fun textRequiresActualStrings() {
        assertEquals("note", PlayerExtrasValuePolicy.text("note"))
        assertNull(PlayerExtrasValuePolicy.text(123))
        assertNull(PlayerExtrasValuePolicy.text("", allowBlank = false))
    }

    @Test fun listeningDayUsesStableIsoShape() {
        assertEquals("2026-08-29", PlayerExtrasValuePolicy.validDay("2026-08-29"))
        assertNull(PlayerExtrasValuePolicy.validDay("29.08.2026"))
        assertNull(PlayerExtrasValuePolicy.validDay(20260829))
    }
}
