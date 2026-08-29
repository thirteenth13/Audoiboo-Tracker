package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveExtractionBudgetTest {
    @Test fun countsFilesAndExpandedBytesAcrossEntries() {
        val budget = ArchiveExtractionBudget()
        budget.beginEntry()
        budget.addBytes(1024)
        budget.beginEntry()
        budget.addBytes(2048)
        assertEquals(2, budget.fileCount())
        assertEquals(3072L, budget.expandedBytes())
    }

    @Test fun zeroByteEntryStillCountsTowardFileLimit() {
        val budget = ArchiveExtractionBudget()
        budget.beginEntry()
        assertEquals(1, budget.fileCount())
        assertEquals(0L, budget.expandedBytes())
    }

    @Test fun negativeByteIncrementIsRejected() {
        val budget = ArchiveExtractionBudget()
        budget.beginEntry()
        var thrown = false
        try { budget.addBytes(-1) } catch (_: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }
}
