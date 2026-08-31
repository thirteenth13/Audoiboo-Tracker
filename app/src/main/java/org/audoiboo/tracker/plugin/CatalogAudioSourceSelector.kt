package org.audoiboo.tracker.plugin

/** Chooses the most useful audio source for importing a catalog series. */
object CatalogAudioSourceSelector {
    data class Candidate(
        val finding: SeriesDiscoveryFinding,
        val matchedBooks: Int,
        val totalBooks: Int
    )

    fun rank(match: CatalogSourceMatch): List<Candidate> = match.sources
        .filter { it.disposition == MatchDisposition.AUTO_ACCEPT }
        .map { finding ->
            Candidate(
                finding = finding,
                matchedBooks = matchedBookCount(match, finding),
                totalBooks = match.canonical.books.size
            )
        }
        .sortedWith(
            compareByDescending<Candidate> { it.matchedBooks }
                .thenByDescending { it.finding.confidence }
                .thenBy { it.finding.sourceId }
        )

    fun best(match: CatalogSourceMatch): Candidate? = rank(match).firstOrNull()

    private fun matchedBookCount(match: CatalogSourceMatch, finding: SeriesDiscoveryFinding): Int {
        val remaining = match.canonical.books.toMutableList()
        var matched = 0

        finding.books.forEach { sourceBook ->
            val result = SourceIdentityMatcher.bestBookMatch(sourceBook, remaining)
                ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                ?: return@forEach
            matched++
            remaining.removeAll { it.id == result.value.id }
        }
        return matched
    }
}
