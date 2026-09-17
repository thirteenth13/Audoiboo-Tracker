package org.audoiboo.tracker.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsProgressEstimateTest {
    @Test fun waitsForMeasuredProgressBeforeShowingEta() {
        val estimate = TtsProgressEstimator.estimate(startChunk = 0, currentChunk = 1, totalChunks = 100, elapsedMs = 5_000)
        assertNull(estimate.remainingMs)
        assertNull(estimate.chunksPerMinute)
    }

    @Test fun estimatesRemainingTimeFromActualChunkRate() {
        val estimate = TtsProgressEstimator.estimate(startChunk = 10, currentChunk = 20, totalChunks = 50, elapsedMs = 60_000)
        assertEquals(20, estimate.completedChunks)
        assertEquals(40, estimate.percent)
        assertEquals(10.0, estimate.chunksPerMinute!!, 0.001)
        assertEquals(180_000L, estimate.remainingMs)
    }

    @Test fun resumeUsesOnlyChunksGeneratedDuringCurrentRunForSpeed() {
        val estimate = TtsProgressEstimator.estimate(startChunk = 80, currentChunk = 85, totalChunks = 100, elapsedMs = 30_000)
        assertEquals(10.0, estimate.chunksPerMinute!!, 0.001)
        assertEquals(90_000L, estimate.remainingMs)
    }

    @Test fun formatsHumanReadableRemainingTime() {
        assertEquals("1 хв", TtsProgressEstimator.formatRemaining(1_000))
        assertEquals("1 год 31 хв", TtsProgressEstimator.formatRemaining(90 * 60_000L + 1))
        assertTrue(TtsProgressEstimator.formatRemaining(2 * 60 * 60_000L).contains("2 год"))
    }
}
