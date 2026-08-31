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
 * expensive audio-source discovery. The strongest provider remains the canonical anchor, while
 * complementary books and author aliases from matching providers are federated into one series.
 */
object CatalogSeriesDeduplicationPolicy {
    fun select(catalogs: List<CatalogDiscoveryResult>): List<CatalogSeriesEntry> {
        val entries = catalogs.flatMap { catalog ->
            catalog.series.map { series -> CatalogSeriesEntry(catalog.providerId, catalog.author, series) }
        }

        return entries
            .groupBy { SourceIdentityMatcher.normalizeTitle(it.series.title) }
            .values
            .flatMap(::clusterByAuthor)
            .mapNotNull(::mergeCluster)
            .sortedWith(
                compareBy<CatalogSeriesEntry> { SourceIdentityMatcher.normalizeAuthor(it.author.name) }
                    .thenBy { SourceIdentityMatcher.normalizeTitle(it.series.title) }
            )
    }

    private fun clusterByAuthor(entries: List<CatalogSeriesEntry>): List<List<CatalogSeriesEntry>> {
        val clusters = mutableListOf<MutableList<CatalogSeriesEntry>>()
        entries.forEach { entry ->
            val matching = clusters.firstOrNull { cluster -> cluster.any { sameAuthor(it.author, entry.author) } }
            if (matching == null) clusters += mutableListOf(entry) else matching += entry
        }
        return clusters
    }

    private fun sameAuthor(left: CatalogAuthor, right: CatalogAuthor): Boolean {
        val leftNames = authorNames(left)
        val rightNames = authorNames(right)
        return leftNames.any { it in rightNames }
    }

    private fun authorNames(author: CatalogAuthor): Set<String> =
        (listOf(author.name) + author.alternativeNames)
            .map(SourceIdentityMatcher::normalizeAuthor)
            .filter(String::isNotBlank)
            .toSet()

    private fun mergeCluster(candidates: List<CatalogSeriesEntry>): CatalogSeriesEntry? {
        val anchor = candidates.maxWithOrNull(entryComparator) ?: return null
        if (candidates.size == 1) return anchor

        val books = linkedMapOf<String, CatalogBook>()
        candidates
            .sortedWith(entryComparator.reversed())
            .forEach { candidate ->
                candidate.series.books.forEach { book ->
                    val key = bookIdentity(book)
                    val current = books[key]
                    books[key] = if (current == null) book else preferBook(current, book)
                }
            }

        val mergedBooks = books.values.sortedWith(
            compareBy<CatalogBook> { it.seriesNumber ?: Double.MAX_VALUE }
                .thenBy { it.firstPublishYear ?: Int.MAX_VALUE }
                .thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }
        )
        val mergedAuthors = candidates
            .flatMap { it.series.authors + it.author.name + it.author.alternativeNames }
            .filter(String::isNotBlank)
            .distinctBy(SourceIdentityMatcher::normalizeAuthor)

        return anchor.copy(
            series = anchor.series.copy(
                authors = mergedAuthors,
                books = mergedBooks
            )
        )
    }

    private fun bookIdentity(book: CatalogBook): String {
        val number = book.seriesNumber
        if (number != null && number.isFinite()) return "number:${number.toString()}"
        return "title:${SourceIdentityMatcher.normalizeTitle(book.title)}"
    }

    private fun preferBook(left: CatalogBook, right: CatalogBook): CatalogBook =
        listOf(left, right).maxWithOrNull(
            compareBy<CatalogBook> { it.seriesNumber != null }
                .thenBy { it.coverUrl != null }
                .thenBy { it.firstPublishYear != null }
                .thenBy { it.authors.size }
        ) ?: left

    private val entryComparator =
        compareBy<CatalogSeriesEntry> { it.author.confidence }
            .thenBy { it.series.books.size }
            .thenBy { it.series.books.count { book -> book.seriesNumber != null } }
            .thenBy { it.series.books.count { book -> book.coverUrl != null } }
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
                    id = "${book.providerId}:${book.remoteId}",
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
