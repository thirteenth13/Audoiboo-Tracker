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
 * Some sites do not have a useful series search, but their normal search can find an author. For
 * those sources we also search by known author names. A provider may also expose a narrower series
 * name than the catalog (for example catalog cycle "Сфера Миров" vs provider series "Игра Кота").
 * In that case the provider series is accepted when its hydrated books strongly overlap canonical
 * books, even if the series titles themselves do not match.
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
        val searchQueries = buildList {
            add(baseQuery)
            canonical.authors
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(3)
                .forEach { author -> add(baseQuery.copy(title = author)) }
            canonical.books
                .sortedBy { it.number ?: Double.MAX_VALUE }
                .take(4)
                .map { it.title.trim() }
                .filter { it.isNotBlank() }
                .forEach { add(baseQuery.copy(title = it)) }
        }.distinctBy { SourceIdentityMatcher.normalizeTitle(it.title) }

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
        val rawCandidateLimit = (maxCandidatesPerSource * 3).coerceAtMost(20)
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
                hits.take(3).forEach { addCandidate(candidates, it, rawCandidateLimit) }
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
                overlapMatch != null -> {
                    SeriesDiscoveryFinding(
                        sourceId = plugin.descriptor.id,
                        series = hydrated,
                        books = books,
                        confidence = overlapMatch.first,
                        disposition = MatchDisposition.AUTO_ACCEPT,
                        evidence = overlapMatch.second
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
     * Fallback for sites whose own series taxonomy differs from the catalog hierarchy.
     * Two strong canonical book matches are enough to link the provider series. One match is enough
     * only when the author also overlaps, which prevents an unrelated same-title book from linking.
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
            add("provider series title may be a nested/alternate catalog series")
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
