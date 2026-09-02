package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginDiagnosticsVerdictTest {
    @Test fun foundMediaIsReported() {
        assertEquals("FOUND", diagnosticMediaVerdict(3, 3, null, null, null))
    }

    @Test fun trackMismatchIsReported() {
        assertEquals("FOUND_TRACK_MISMATCH", diagnosticMediaVerdict(1, 3, null, null, null))
    }

    @Test fun filteredRawMediaIsReported() {
        assertEquals("RAW_FOUND_BUT_FILTERED", diagnosticMediaVerdict(0, 20, 12, 0, 20))
    }

    @Test fun acceptedRawMediaMissIsReported() {
        assertEquals("RAW_ACCEPTED_BUT_CAPTURE_MISSED", diagnosticMediaVerdict(0, 5, 2, 1, 5))
    }

    @Test fun inactivePlayerIsReported() {
        assertEquals("PLAYER_NOT_ACTIVATED_OR_NO_TRACKS", diagnosticMediaVerdict(0, 0, 0, 0, 0))
    }
}
