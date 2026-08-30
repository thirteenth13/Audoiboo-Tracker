package org.audoiboo.tracker.plugin

/**
 * Conservative guard for source pages that accidentally expose books from related/neighbor series.
 * If a book declares a series, it must match the resolved series exactly after normalization.
 * Sources that cannot provide per-book series metadata remain unchanged.
 */
object SeriesBookMembershipPolicy {
    fun belongsTo(series: SourceSeries, book: SourceBook): Boolean {
        val declared = book.seriesTitle?.takeIf { it.isNotBlank() } ?: return true
        val expected = SourceIdentityMatcher.normalizeTitle(series.title)
        val actual = SourceIdentityMatcher.normalizeTitle(declared)
        return expected.isNotBlank() && expected == actual
    }

    fun filter(series: SourceSeries, books: List<SourceBook>): List<SourceBook> =
        books.filter { belongsTo(series, it) }
}
