package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginDiagnosticsSeriesStrategyTest {
    @Test fun verdictDoesNotRequireRawProbeWhenCaptureSucceeds() {
        assertEquals("FOUND", diagnosticMediaVerdict(2, 2, null, null, null))
    }

    @Test fun emptyRawProbeWithoutActivationIsActionable() {
        assertEquals("PLAYER_NOT_ACTIVATED_OR_NO_TRACKS", diagnosticMediaVerdict(0, null, 0, 0, 0))
    }
}
