package org.audoiboo.tracker

/** Runtime counter used while extracting, independent of ZIP metadata verified beforehand. */
internal class ArchiveExtractionBudget {
    private var files = 0
    private var totalBytes = 0L
    private var currentEntryBytes = 0L

    fun beginEntry() {
        files++
        currentEntryBytes = 0L
        check(ArchiveSafetyPolicy.validateTotals(files, totalBytes)) { "ZIP перевищує безпечну кількість файлів" }
    }

    fun addBytes(count: Int) {
        require(count >= 0)
        currentEntryBytes += count.toLong()
        totalBytes += count.toLong()
        check(currentEntryBytes <= ArchiveSafetyPolicy.MAX_SINGLE_FILE_BYTES) { "Файл у ZIP перевищує безпечний розмір" }
        check(ArchiveSafetyPolicy.validateTotals(files, totalBytes)) { "ZIP перевищує безпечний розпакований обсяг" }
    }

    internal fun fileCount(): Int = files
    internal fun expandedBytes(): Long = totalBytes
}
