package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadConcurrencyGateTest {
    @Test fun onlyConfiguredNumberOfTransfersCanRun() {
        val gate = DownloadConcurrencyGate(2)
        assertTrue(gate.tryAcquire())
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }

    @Test fun releasedPermitCanBeReused() {
        val gate = DownloadConcurrencyGate(1)
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        gate.release()
        assertTrue(gate.tryAcquire())
    }

    @Test fun nonPositiveLimitStillAllowsOneTransfer() {
        val gate = DownloadConcurrencyGate(0)
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }
}
