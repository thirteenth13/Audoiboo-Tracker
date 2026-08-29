package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveExtractionBudgetRegressionTest {
    @Test fun zeroByteFilesStillIncreaseFileCount() {
        val budget = ArchiveExtractionBudget()
        repeat(3) { budget.beginEntry() }
        assertEquals(3, budget.fileCount())
        assertEquals(0L, budget.expandedBytes())
    }

    @Test fun byteCountersAccumulateAcrossEntries() {
        val budget = ArchiveExtractionBudget()
        budget.beginEntry(); budget.addBytes(10)
        budget.beginEntry(); budget.addBytes(20)
        assertEquals(30L, budget.expandedBytes())
    }

    @Test fun invalidNegativeChunkIsRejected() {
        val budget = ArchiveExtractionBudget()
        budget.beginEntry()
        var failed = false
        try { budget.addBytes(-1) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }
}
