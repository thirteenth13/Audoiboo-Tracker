package org.audoiboo.tracker.plugin

/**
 * Conservative guard for source pages that accidentally expose books from related/neighbor series.
 *
 * A declared per-book series must match the resolved series. Some sources, however, flatten a
 * subseries into its parent and still declare only the parent name. In that case a title such as
 * "Звездная Кровь. Белый Дьявол 02 ..." carries a stronger structural signal than the flattened
 * metadata: after the parent series name there is a named qualifier before a new volume number.
 * Plain parent-series titles such as "Звездная Кровь 08 ..." remain accepted.
 */
object SeriesBookMembershipPolicy {
    fun belongsTo(series: SourceSeries, book: SourceBook): Boolean {
        val declared = book.seriesTitle?.takeIf { it.isNotBlank() } ?: return true
        val expected = SourceIdentityMatcher.normalizeTitle(series.title)
        val actual = SourceIdentityMatcher.normalizeTitle(declared)
        if (expected.isBlank() || expected != actual) return false
        return !hasNestedSeriesQualifier(expected, SourceIdentityMatcher.normalizeTitle(book.title))
    }

    fun filter(series: SourceSeries, books: List<SourceBook>): List<SourceBook> =
        books.filter { belongsTo(series, it) }

    private fun hasNestedSeriesQualifier(expected: String, bookTitle: String): Boolean {
        val start = bookTitle.indexOf(expected)
        if (start < 0) return false
        val suffix = bookTitle.substring(start + expected.length).trim()
        if (suffix.isBlank()) return false

        val tokens = suffix.split(' ').filter { it.isNotBlank() }
        val firstNumber = tokens.indexOfFirst(::isVolumeNumber)
        if (firstNumber <= 0) return false

        return tokens.take(firstNumber).any { token -> token.any(Char::isLetter) }
    }

    private fun isVolumeNumber(token: String): Boolean =
        token.toDoubleOrNull() != null || Regex("^\\d+(?:[.-]\\d+)*$").matches(token)
}
