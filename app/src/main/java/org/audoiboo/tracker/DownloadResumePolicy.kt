package org.audoiboo.tracker

/** Pure HTTP range rules used before appending to an existing staging file. */
internal object DownloadResumePolicy {
    data class ContentRange(val start: Long, val endInclusive: Long, val total: Long?)

    fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val match = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$", RegexOption.IGNORE_CASE)
            .matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (end < start) return null
        if (total != null && (total <= 0L || end >= total)) return null
        return ContentRange(start, end, total)
    }

    fun canAppend(existingBytes: Long, responseCode: Int, contentRange: String?): Boolean {
        if (existingBytes <= 0L || responseCode != 206) return false
        return parseContentRange(contentRange)?.start == existingBytes
    }

    fun expectedTotal(startAt: Long, contentLength: Long, contentRange: String?): Long {
        val rangeTotal = parseContentRange(contentRange)?.total
        if (rangeTotal != null) return rangeTotal
        return if (contentLength > 0L) startAt + contentLength else -1L
    }
}
