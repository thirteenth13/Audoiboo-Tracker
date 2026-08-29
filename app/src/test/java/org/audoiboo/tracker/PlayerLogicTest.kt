package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLogicTest {
    @Test fun smartRewindBoundaries() {
        assertEquals(0L, PlayerLogic.smartRewindForGapMs(5 * 60_000L - 1))
        assertEquals(10_000L, PlayerLogic.smartRewindForGapMs(5 * 60_000L))
        assertEquals(10_000L, PlayerLogic.smartRewindForGapMs(60 * 60_000L - 1))
        assertEquals(30_000L, PlayerLogic.smartRewindForGapMs(60 * 60_000L))
        assertEquals(30_000L, PlayerLogic.smartRewindForGapMs(24 * 60 * 60_000L - 1))
        assertEquals(60_000L, PlayerLogic.smartRewindForGapMs(24 * 60 * 60_000L))
    }

    @Test fun statusThresholds() {
        assertEquals("NEW", PlayerLogic.statusForProgress(0f, false))
        assertEquals("READING", PlayerLogic.statusForProgress(.01f, true))
        assertEquals("READING", PlayerLogic.statusForProgress(.94f, true))
        assertEquals("READ", PlayerLogic.statusForProgress(.95f, true))
        assertEquals("READ", PlayerLogic.statusForProgress(1f, true))
    }

    @Test fun aggregateProgressHandlesEdges() {
        assertEquals(0f, PlayerLogic.aggregateProgress(0, 0, 0, 0))
        assertEquals(.5f, PlayerLogic.aggregateProgress(1, 0, 5_000, 10_000), .0001f)
        assertEquals(.625f, PlayerLogic.aggregateProgress(4, 2, 5_000, 10_000), .0001f)
        assertEquals(1f, PlayerLogic.aggregateProgress(1, 0, 50_000, 10_000), .0001f)
    }

    @Test fun uriBrokenSetMapsBackToStableTrackIndices() {
        val uris = listOf("content://a", "content://b", "content://c", "content://d")
        val broken = PlayerLogic.brokenIndices(uris, setOf("content://b", "content://d", "content://missing"))
        assertEquals(setOf(1, 3), broken)
        assertEquals(2, PlayerLogic.playableCount(uris.size, broken))
        assertEquals(4, PlayerLogic.playableCount(uris.size, emptySet()))
        assertEquals(0, PlayerLogic.playableCount(0, broken))
    }

    @Test fun emptyAndSingleTrackBooksAreStable() {
        val empty = PlayerLogic.bookProgress(emptyList(), emptyList())
        assertEquals(0f, empty.fraction)
        assertFalse(empty.started)
        assertFalse(empty.finished)
        assertEquals(0, empty.playableCount)

        val half = PlayerLogic.bookProgress(listOf(5_000L), listOf(10_000L))
        assertEquals(.5f, half.fraction, .0001f)
        assertTrue(half.started)
        assertFalse(half.finished)
        assertEquals(1, half.currentTrack)

        val done = PlayerLogic.bookProgress(listOf(9_500L), listOf(10_000L))
        assertEquals(1f, done.fraction, .0001f)
        assertTrue(done.finished)
    }

    @Test fun brokenTracksDoNotBlockBookCompletion() {
        val progress = PlayerLogic.bookProgress(
            positionsMs = listOf(10_000L, 0L, 9_500L),
            durationsMs = listOf(10_000L, 10_000L, 10_000L),
            brokenIndices = setOf(1)
        )
        assertTrue(progress.finished)
        assertEquals(1f, progress.fraction, .0001f)
        assertEquals(2, progress.playableCount)
        assertEquals(3, progress.currentTrack)
    }

    @Test fun unknownDurationCanStartButCannotAutoFinish() {
        val progress = PlayerLogic.bookProgress(listOf(42_000L), listOf(0L))
        assertTrue(progress.started)
        assertFalse(progress.finished)
        assertEquals(0f, progress.fraction, .0001f)
    }

    @Test fun trackerReadWinsEvenWithoutPlayableFiles() {
        val emptyRead = PlayerLogic.bookProgress(emptyList(), emptyList(), trackerRead = true)
        assertTrue(emptyRead.finished)
        assertEquals(1f, emptyRead.fraction)

        val allBrokenRead = PlayerLogic.bookProgress(
            positionsMs = listOf(1_000L),
            durationsMs = listOf(10_000L),
            brokenIndices = setOf(0),
            trackerRead = true
        )
        assertTrue(allBrokenRead.finished)
        assertEquals(0, allBrokenRead.playableCount)
    }

    @Test fun currentProgressExcludesBrokenTracks() {
        assertEquals(.75f, PlayerLogic.currentBookProgress(3, 2, 5_000L, 10_000L, setOf(1)), .0001f)
        assertEquals(0f, PlayerLogic.currentBookProgress(3, 1, 5_000L, 10_000L, setOf(1)), .0001f)
        assertEquals(0f, PlayerLogic.currentBookProgress(0, 0, 0L, 0L), .0001f)
    }

    @Test fun resumePositionNeverEscapesKnownDuration() {
        assertEquals(8_000L, PlayerLogic.safeResumePosition(10_000L, 20_000L, 2_000L))
        assertEquals(18_000L, PlayerLogic.safeResumePosition(50_000L, 20_000L, 2_000L))
        assertEquals(0L, PlayerLogic.safeResumePosition(1_000L, 20_000L, 5_000L))
        assertEquals(9_000L, PlayerLogic.safeResumePosition(10_000L, 0L, 1_000L))
    }

    @Test fun brokenTrackNavigationSkipsKnownFailures() {
        val broken = setOf(1, 2, 4)
        assertEquals(3, PlayerLogic.nextPlayableIndex(6, 0, broken))
        assertEquals(5, PlayerLogic.nextPlayableIndex(6, 3, broken))
        assertNull(PlayerLogic.nextPlayableIndex(6, 5, broken))
        assertEquals(3, PlayerLogic.previousPlayableIndex(6, 5, broken))
        assertEquals(0, PlayerLogic.previousPlayableIndex(6, 3, broken))
        assertNull(PlayerLogic.previousPlayableIndex(6, 0, broken))
    }

    @Test fun resumeMovesOffBrokenPersistedTrack() {
        val broken = setOf(1, 2)
        assertEquals(0, PlayerLogic.resumePlayableIndex(4, 0, broken))
        assertEquals(3, PlayerLogic.resumePlayableIndex(4, 1, broken))
        assertEquals(0, PlayerLogic.resumePlayableIndex(3, 2, setOf(1, 2)))
        assertNull(PlayerLogic.resumePlayableIndex(2, 0, setOf(0, 1)))
        assertNull(PlayerLogic.resumePlayableIndex(0, 0, emptySet()))
    }

    @Test fun parsesRealisticSeriesNumbers() {
        assertEquals(26, PlayerLogic.parseBookNumber("Другая сторона 26"))
        assertEquals(5, PlayerLogic.parseBookNumber("Книга 5 (2)"))
        assertEquals(26, PlayerLogic.parseBookNumber("26 - Назва"))
        assertEquals(3, PlayerLogic.parseBookNumber("Частина 3"))
        assertNull(PlayerLogic.parseBookNumber("Книга без номера"))
    }

    @Test fun queueMoveAndPlayNextAreStable() {
        assertEquals(listOf("a", "c", "b"), PlayerLogic.moveQueue(listOf("a", "b", "c"), 2, 1))
        assertEquals(listOf("a", "x", "b", "c"), PlayerLogic.playNext(listOf("a", "b", "c"), "a", "x"))
        assertEquals(listOf("a", "b", "c"), PlayerLogic.playNext(listOf("a", "b", "c"), "a", "b"))
    }
}
