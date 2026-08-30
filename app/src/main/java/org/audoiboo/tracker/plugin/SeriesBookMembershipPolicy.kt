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
        return inferredNestedSeriesKey(series, book) == null
    }

    fun filter(series: SourceSeries, books: List<SourceBook>): List<SourceBook> =
        books.filter { belongsTo(series, it) }

    /**
     * Returns a normalized candidate subseries title when the book title has the form
     * "<parent series> <named qualifier> <volume number>". The value is intended only for matching
     * an already-known canonical series; callers should not create a new series from this signal.
     */
    fun inferredNestedSeriesKey(series: SourceSeries, book: SourceBook): String? {
        val expected = SourceIdentityMatcher.normalizeTitle(series.title)
        val bookTitle = SourceIdentityMatcher.normalizeTitle(book.title)
        if (expected.isBlank()) return null
        val start = bookTitle.indexOf(expected)
        if (start < 0) return null
        val suffix = bookTitle.substring(start + expected.length).trim()
        if (suffix.isBlank()) return null

        val tokens = suffix.split(' ').filter { it.isNotBlank() }
        val firstNumber = tokens.indexOfFirst(::isVolumeNumber)
        if (firstNumber <= 0) return null
        val qualifier = tokens.take(firstNumber).filter { token -> token.any(Char::isLetter) }
        if (qualifier.isEmpty()) return null
        return (listOf(expected) + qualifier).joinToString(" ")
    }

    private fun isVolumeNumber(token: String): Boolean =
        token.toDoubleOrNull() != null || Regex("^\\d+(?:[.-]\\d+)*$").matches(token)
}
