package org.audoiboo.tracker.plugin

/** One concrete audio-source observation matched to a catalog book. */
data class CatalogBookSourceAvailability(
    val sourceId: String,
    val sourceBook: SourceBook,
    val confidence: Float,
    val disposition: MatchDisposition,
    val evidence: List<String>
)

/** Per-book availability projection used by the catalog UI. */
data class CatalogBookAvailability(
    val canonicalBookId: String,
    val catalogBook: CatalogBook,
    val sources: List<CatalogBookSourceAvailability>
)

object CatalogBookAvailabilityResolver {
    fun resolve(match: CatalogSourceMatch): List<CatalogBookAvailability> =
        match.canonical.books.mapIndexed { index, canonicalBook ->
            val catalogBook = match.series.books[index]
            val sources = match.sources
                .asSequence()
                .filter { it.disposition != MatchDisposition.REJECT }
                .flatMap { finding ->
                    finding.books.asSequence().mapNotNull { sourceBook ->
                        val identity = SourceIdentityMatcher.bestBookMatch(
                            incoming = sourceBook,
                            candidates = listOf(canonicalBook)
                        ) ?: return@mapNotNull null
                        if (identity.disposition == MatchDisposition.REJECT) return@mapNotNull null
                        CatalogBookSourceAvailability(
                            sourceId = finding.sourceId,
                            sourceBook = sourceBook,
                            confidence = identity.confidence,
                            disposition = identity.disposition,
                            evidence = identity.evidence
                        )
                    }
                }
                .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.sourceBook.url) }
                .sortedWith(
                    compareByDescending<CatalogBookSourceAvailability> { it.disposition == MatchDisposition.AUTO_ACCEPT }
                        .thenByDescending { it.confidence }
                        .thenBy { it.sourceId }
                )
                .toList()

            CatalogBookAvailability(
                canonicalBookId = canonicalBook.id,
                catalogBook = catalogBook,
                sources = sources
            )
        }
}
