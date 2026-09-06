package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/** A source candidate linked back to one canonical series by the explainable matcher. */
data class SeriesDiscoveryFinding(
    val sourceId: String,
    val series: SourceSeries,
    val books: List<SourceBook>,
    val confidence: Float,
    val disposition: MatchDisposition,
    val evidence: List<String>
)

/**
 * Searches enabled SERIES_SEARCH providers and also runs bounded SERIES_DISCOVERY providers for
 * sources that cannot expose a normal text search. Independent sources run concurrently, while
 * work inside one source stays sequential to avoid flooding a site. Individual failures are isolated.
 *
 * The catalog is the source of truth for membership: when we already know the author and the list
 * of books in a canonical series, providers are searched for those books one by one. This does not
 * require the provider to use the same series/cycle title as the catalog. The provider series title
 * is only a helpful signal; strong canonical-book matches are enough to link source books.
 */
class SourceDiscoveryEngine(
    private val registry: SourcePluginRegistry,
    private val maxCandidatesPerSource: Int = 5
) {
    init {
        require(maxCandidatesPerSource in 1..20)
    }

    suspend fun discoverSeries(
        canonical: CanonicalSeriesMatchInput,
        excludeSourceId: String? = null
    ): List<SeriesDiscoveryFinding> {
        val baseQuery = SeriesSearchQuery(
            title = canonical.title,
            knownAuthors = canonical.authors,
            knownBooks = canonical.books.map { KnownBook(it.title, it.number, it.authors) }
        )

        val normalizedSeriesTitle = SourceIdentityMatcher.normalizeTitle(canonical.title)
        val authors = canonical.authors
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy(SourceIdentityMatcher::normalizeAuthor)
            .take(3)

        val targetedBookQueries = canonical.books
            .sortedBy { it.number ?: Double.MAX_VALUE }
            .flatMap { book ->
                val title = book.title.trim()
                if (title.isBlank()) return@flatMap emptyList()

                val bookAuthors = (book.authors + authors)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinctBy(SourceIdentityMatcher::normalizeAuthor)
                    .take(2)

                buildList {
                    // Most site search boxes are simple text search, so put author and title into
                    // the actual query string instead of relying only on knownAuthors metadata.
                    bookAuthors.forEach { author -> add(baseQuery.copy(title = "$author $title")) }
                    add(baseQuery.copy(title = title))
                }
            }

        val fallbackQueries = buildList {
            add(baseQuery)
            authors.forEach { author -> add(baseQuery.copy(title = author)) }
        }

        val searchQueries = (targetedBookQueries + fallbackQueries)
            .distinctBy { SourceIdentityMatcher.normalizeTitle(it.title) }
            .filter { SourceIdentityMatcher.normalizeTitle(it.title) != normalizedSeriesTitle || it.title == canonical.title }

        val findings = supervisorScope {
            registry.plugins
                .filterNot { it.descriptor.id == excludeSourceId }
                .filter { plugin ->
                    SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities ||
                        SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities
                }
                .map { plugin -> async { discoverSource(plugin, canonical, searchQueries) } }
                .awaitAll()
                .flatten()
        }

        return findings
            .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.series.url) }
            .sortedWith(compareByDescending<SeriesDiscoveryFinding> { it.confidence }.thenBy { it.sourceId })
    }

    private suspend fun discoverSource(
        plugin: SourcePlugin,
        canonical: CanonicalSeriesMatchInput,
        searchQueries: List<SeriesSearchQuery>
    ): List<SeriesDiscoveryFinding> {
        // Book-by-book lookup needs a wider pool than generic series discovery. Keep it bounded, but
        // large enough that several known volumes can each contribute a hit.
        val rawCandidateLimit = (canonical.books.size.coerceAtLeast(maxCandidatesPerSource) * 3)
            .coerceIn(maxCandidatesPerSource * 3, 60)
        val candidates = mutableListOf<SeriesCandidate>()

        val directDiscovery = plugin as? SeriesDiscoveryProvider
        if (SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities && directDiscovery != null) {
            val hits = try {
                directDiscovery.discoverSeries(canonical)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                emptyList()
            }
            hits.take(3).forEach { addCandidate(candidates, it, rawCandidateLimit) }
        }

        val search = plugin as? SeriesSearchProvider
        if (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities && search != null) {
            searchQueries.forEach queryLoop@ { query ->
                if (candidates.size >= rawCandidateLimit) return@queryLoop
                val hits = try {
                    search.searchSeries(query)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    return@queryLoop
                }
                // For a targeted book query the best result is normally first, but keep two in case
                // the site ranks a text/ebook page before the audiobook page.
                hits.take(2).forEach { addCandidate(candidates, it, rawCandidateLimit) }
            }
        }

        val findings = mutableListOf<SeriesDiscoveryFinding>()
        candidates.forEach candidateLoop@ { candidate ->
            if (candidate.series.sourceId != plugin.descriptor.id) return@candidateLoop
            val provider = plugin as? SeriesProvider
            val hydrated = if (provider != null) {
                try {
                    provider.resolveSeries(candidate.series.url) ?: candidate.series
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    candidate.series
                }
            } else candidate.series
            if (hydrated.sourceId != plugin.descriptor.id) return@candidateLoop

            val books = if (provider != null) {
                try {
                    provider.loadSeriesBooks(hydrated)
                        .filter { it.sourceId == plugin.descriptor.id }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    emptyList()
                }
            } else emptyList()

            val seriesMatch = SourceIdentityMatcher.bestSeriesMatch(
                incoming = hydrated,
                incomingBooks = books,
                candidates = listOf(canonical)
            )

            val overlapMatch = matchByCanonicalBooks(books, canonical)
            val accepted = when {
                overlapMatch != null -> {
                    // Prefer direct canonical-book evidence over provider taxonomy. This is the key
                    // path when, for example, a site calls a subset "Игра Кота" while the catalog
                    // groups those books under a wider cycle.
                    SeriesDiscoveryFinding(
                        sourceId = plugin.descriptor.id,
                        series = hydrated,
                        books = books,
                        confidence = overlapMatch.first,
                        disposition = MatchDisposition.AUTO_ACCEPT,
                        evidence = overlapMatch.second
                    )
                }
                seriesMatch != null && seriesMatch.disposition != MatchDisposition.REJECT -> {
                    SeriesDiscoveryFinding(
                        sourceId = plugin.descriptor.id,
                        series = hydrated,
                        books = books,
                        confidence = seriesMatch.confidence,
                        disposition = seriesMatch.disposition,
                        evidence = seriesMatch.evidence
                    )
                }
                else -> null
            }

            if (accepted != null) findings += accepted
        }

        return findings
            .sortedByDescending { it.confidence }
            .take(maxCandidatesPerSource)
    }

    /**
     * Provider taxonomy may differ from the catalog hierarchy. Two strong canonical book matches
     * are enough to link the provider series. One match is enough only when its author also overlaps.
     */
    private fun matchByCanonicalBooks(
        books: List<SourceBook>,
        canonical: CanonicalSeriesMatchInput
    ): Pair<Float, List<String>>? {
        if (books.isEmpty() || canonical.books.isEmpty()) return null

        val matchedCanonicalIds = linkedSetOf<String>()
        var authorSupportedMatches = 0
        books.forEach { incoming ->
            val match = SourceIdentityMatcher.bestBookMatch(incoming, canonical.books)
                ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                ?: return@forEach
            if (!matchedCanonicalIds.add(match.value.id)) return@forEach

            val incomingAuthors = incoming.authors
                .map { SourceIdentityMatcher.normalizeAuthor(it.name) }
                .filter { it.isNotBlank() }
                .toSet()
            val canonicalAuthors = match.value.authors
                .map(SourceIdentityMatcher::normalizeAuthor)
                .filter { it.isNotBlank() }
                .toSet()
            if (incomingAuthors.isNotEmpty() && canonicalAuthors.isNotEmpty() &&
                incomingAuthors.intersect(canonicalAuthors).isNotEmpty()
            ) {
                authorSupportedMatches++
            }
        }

        val count = matchedCanonicalIds.size
        val accepted = count >= 2 || (count == 1 && authorSupportedMatches == 1)
        if (!accepted) return null

        val confidence = (0.95f + (count - 1).coerceAtLeast(0) * 0.01f).coerceAtMost(0.99f)
        return confidence to buildList {
            add("canonical book overlap: $count")
            if (authorSupportedMatches > 0) add("author-supported book matches: $authorSupportedMatches")
            add("provider series title is not required to match canonical series title")
        }
    }

    private fun addCandidate(
        target: MutableList<SeriesCandidate>,
        candidate: SeriesCandidate,
        limit: Int = maxCandidatesPerSource
    ) {
        if (target.size >= limit) return
        val normalized = SourceKeys.normalizeUrl(candidate.series.url)
        if (target.none { SourceKeys.normalizeUrl(it.series.url) == normalized }) target += candidate
    }
}
