package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
