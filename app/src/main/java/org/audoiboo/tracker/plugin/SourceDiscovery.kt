package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException

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
 * Searches all enabled SERIES_SEARCH providers for alternate observations of a canonical series.
 * Individual plugin failures are isolated so a broken source cannot abort discovery on other sources.
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
        val query = SeriesSearchQuery(
            title = canonical.title,
            knownAuthors = canonical.authors,
            knownBooks = canonical.books.map { KnownBook(it.title, it.number, it.authors) }
        )
        val findings = mutableListOf<SeriesDiscoveryFinding>()

        registry.withCapability(SourceCapability.SERIES_SEARCH)
            .filterNot { it.descriptor.id == excludeSourceId }
            .forEach pluginLoop@ { plugin ->
                val search = plugin as? SeriesSearchProvider ?: return@pluginLoop
                val candidates = try {
                    search.searchSeries(query).take(maxCandidatesPerSource)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    return@pluginLoop
                }

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
            }

        return findings
            .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.series.url) }
            .sortedWith(compareByDescending<SeriesDiscoveryFinding> { it.confidence }.thenBy { it.sourceId })
    }
}
