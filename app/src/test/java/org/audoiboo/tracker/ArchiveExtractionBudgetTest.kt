package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class ArchiveExtractionBudgetTest {
    @Test fun countsFilesAndActualExpandedBytes() {
        val budget = ArchiveExtractionBudget()
        budget.beginEntry(); budget.addBytes(100); budget.addBytes(50)
        budget.beginEntry(); budget.addBytes(25)
        assertEquals(2, budget.fileCount())
        assertEquals(175L, budget.expandedBytes())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsTooManyFiles() {
        val budget = ArchiveExtractionBudget()
        repeat(ArchiveSafetyPolicy.MAX_FILES + 1) { budget.beginEntry() }
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsOversizedSingleEntryFromActualBytes() {
        val budget = ArchiveExtractionBudget()
        budget.beginEntry()
        var remaining = ArchiveSafetyPolicy.MAX_SINGLE_FILE_BYTES + 1
        while (remaining > 0) {
            val chunk = minOf(Int.MAX_VALUE.toLong(), remaining).toInt()
            budget.addBytes(chunk)
            remaining -= chunk
        }
    }
}
