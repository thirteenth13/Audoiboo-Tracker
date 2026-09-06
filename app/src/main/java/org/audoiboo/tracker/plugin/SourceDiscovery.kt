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
 * those sources we also search by known author names, then hydrate the returned book pages and let
 * the normal series matcher decide whether the linked series belongs to the canonical entry.
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
            // Prefer the exact series title first.
            add(baseQuery)

            // Author fallback: on several audiobook sites this is more reliable than series search.
            canonical.authors
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(3)
                .forEach { author -> add(baseQuery.copy(title = author)) }

            // Known volume titles are the final bounded fallback.
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
        // Keep a wider raw pool than the final result cap so one noisy query cannot prevent the
        // author fallback from being considered. Limit each query to two fresh hits for fairness.
        val rawCandidateLimit = (maxCandidatesPerSource * 2).coerceAtMost(20)
        val candidates = mutableListOf<SeriesCandidate>()

        val directDiscovery = plugin as? SeriesDiscoveryProvider
        if (SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities && directDiscovery != null) {
            val hits = try {
                directDiscovery.discoverSeries(canonical)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                emptyList()
            }
            hits.take(2).forEach { addCandidate(candidates, it, rawCandidateLimit) }
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

            val match = SourceIdentityMatcher.bestSeriesMatch(
                incoming = hydrated,
                incomingBooks = books,
                candidates = listOf(canonical)
            ) ?: return@candidateLoop
            if (match.disposition != MatchDisposition.REJECT) {
                findings += SeriesDiscoveryFinding(
                    sourceId = plugin.descriptor.id,
                    series = hydrated,
                    books = books,
                    confidence = match.confidence,
                    disposition = match.disposition,
                    evidence = match.evidence
                )
            }
        }
        return findings
            .sortedByDescending { it.confidence }
            .take(maxCandidatesPerSource)
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
