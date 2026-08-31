package org.audoiboo.tracker.plugin

/** Result of resolving one bibliographic catalog series against audio-source plugins. */
data class CatalogSourceMatch(
    val catalogProviderId: String,
    val author: CatalogAuthor,
    val series: CatalogSeries,
    val canonical: CanonicalSeriesMatchInput,
    val sources: List<SeriesDiscoveryFinding>
)

data class CatalogSeriesEntry(
    val providerId: String,
    val author: CatalogAuthor,
    val series: CatalogSeries
)

/**
 * Collapses the same author/series reported by multiple bibliographic providers before doing
 * expensive audio-source discovery. The strongest catalog candidate wins while provider identity
 * is preserved for stable canonical IDs and source exclusion.
 */
object CatalogSeriesDeduplicationPolicy {
    fun select(catalogs: List<CatalogDiscoveryResult>): List<CatalogSeriesEntry> =
        catalogs
            .flatMap { catalog ->
                catalog.series.map { series -> CatalogSeriesEntry(catalog.providerId, catalog.author, series) }
            }
            .groupBy { entry ->
                SourceIdentityMatcher.normalizeAuthor(entry.author.name) to
                    SourceIdentityMatcher.normalizeTitle(entry.series.title)
            }
            .values
            .mapNotNull { candidates ->
                candidates.maxWithOrNull(
                    compareBy<CatalogSeriesEntry> { it.author.confidence }
                        .thenBy { it.series.books.size }
                        .thenBy { it.series.books.count { book -> book.seriesNumber != null } }
                        .thenBy { it.series.books.count { book -> book.coverUrl != null } }
                )
            }
            .sortedWith(
                compareBy<CatalogSeriesEntry> { SourceIdentityMatcher.normalizeAuthor(it.author.name) }
                    .thenBy { SourceIdentityMatcher.normalizeTitle(it.series.title) }
            )
}

/** Stable canonical projection used by the existing explainable source matcher. */
object CatalogCanonicalMapper {
    fun seriesId(providerId: String, authorRemoteId: String, seriesTitle: String): String =
        listOf(providerId, authorRemoteId, SourceIdentityMatcher.normalizeTitle(seriesTitle))
            .joinToString(":")

    fun toCanonical(providerId: String, author: CatalogAuthor, series: CatalogSeries): CanonicalSeriesMatchInput =
        CanonicalSeriesMatchInput(
            id = seriesId(providerId, author.remoteId, series.title),
            title = series.title,
            authors = (series.authors + author.name).filter(String::isNotBlank).distinct(),
            books = series.books.map { book ->
                CanonicalBookMatchInput(
                    id = "$providerId:${book.remoteId}",
                    title = book.title,
                    authors = if (book.authors.isNotEmpty()) book.authors else listOf(author.name),
                    number = book.seriesNumber
                )
            }
        )
}

/**
 * Orchestrates bibliographic discovery first, then reuses SourceDiscoveryEngine to locate
 * matching audiobook series on enabled source plugins. Catalog providers themselves are excluded
 * from source matching because they describe identity, not playable/downloadable media.
 */
class CatalogSourceBridge(
    private val registry: SourcePluginRegistry,
    private val catalogDiscovery: CatalogDiscoveryEngine = CatalogDiscoveryEngine(registry),
    private val sourceDiscovery: SourceDiscoveryEngine = SourceDiscoveryEngine(registry)
) {
    suspend fun discoverByAuthor(authorQuery: String): List<CatalogSourceMatch> {
        val catalogs = catalogDiscovery.discoverByAuthor(authorQuery)
        return CatalogSeriesDeduplicationPolicy.select(catalogs).map { entry ->
            val canonical = CatalogCanonicalMapper.toCanonical(entry.providerId, entry.author, entry.series)
            val findings = sourceDiscovery.discoverSeries(
                canonical = canonical,
                excludeSourceId = entry.providerId
            )
            CatalogSourceMatch(
                catalogProviderId = entry.providerId,
                author = entry.author,
                series = entry.series,
                canonical = canonical,
                sources = findings
            )
        }
    }
}
