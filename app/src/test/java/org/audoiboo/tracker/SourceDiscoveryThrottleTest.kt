package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceDiscoveryThrottleTest {
    @Test
    fun firstDiscoveryRunsImmediately() {
        assertTrue(SourceDiscoveryThrottle.shouldRun(lastSuccessAt = 0L, now = 1_000L))
    }

    @Test
    fun recentSuccessfulDiscoveryIsThrottled() {
        val interval = 6L * 60L * 60L * 1000L
        assertFalse(
            SourceDiscoveryThrottle.shouldRun(
                lastSuccessAt = 1_000L,
                now = 1_000L + interval - 1L,
                intervalMs = interval
            )
        )
        assertTrue(
            SourceDiscoveryThrottle.shouldRun(
                lastSuccessAt = 1_000L,
                now = 1_000L + interval,
                intervalMs = interval
            )
        )
    }

    @Test
    fun forcedDiscoveryBypassesThrottle() {
        assertTrue(
            SourceDiscoveryThrottle.shouldRun(
                lastSuccessAt = 10_000L,
                now = 10_001L,
                force = true
            )
        )
    }

    @Test
    fun clockMovingBackwardsDoesNotSuppressDiscovery() {
        assertTrue(SourceDiscoveryThrottle.shouldRun(lastSuccessAt = 20_000L, now = 10_000L))
    }
}
