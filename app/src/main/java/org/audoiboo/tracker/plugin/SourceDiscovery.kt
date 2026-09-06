package org.audoiboo.tracker.plugin

import android.util.Log
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
    companion object {
        private const val TAG = "AudoibooSeries"
    }

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

        val providers = registry.plugins
            .filterNot { it.descriptor.id == excludeSourceId }
            .filter { plugin ->
                SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities ||
                    SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities
            }

        Log.i(
            TAG,
            "discovery START canonical='${canonical.title}' id=${canonical.id} books=${canonical.books.size} authors=${authors.joinToString()} queries=${searchQueries.size} exclude=$excludeSourceId providers=${providers.joinToString { it.descriptor.id }}"
        )

        val findings = supervisorScope {
            providers
                .map { plugin -> async { discoverSource(plugin, canonical, searchQueries) } }
                .awaitAll()
                .flatten()
        }

        val result = findings
            .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.series.url) }
            .sortedWith(compareByDescending<SeriesDiscoveryFinding> { it.confidence }.thenBy { it.sourceId })

        Log.i(
            TAG,
            "discovery END canonical='${canonical.title}' findings=${result.size} bySource=${result.groupingBy { it.sourceId }.eachCount()}"
        )
        return result
    }

    private suspend fun discoverSource(
        plugin: SourcePlugin,
        canonical: CanonicalSeriesMatchInput,
        searchQueries: List<SeriesSearchQuery>
    ): List<SeriesDiscoveryFinding> {
        val id = plugin.descriptor.id
        val rawCandidateLimit = (canonical.books.size.coerceAtLeast(maxCandidatesPerSource) * 3)
            .coerceIn(maxCandidatesPerSource * 3, 60)
        val candidates = mutableListOf<SeriesCandidate>()
        var directHitCount = 0
        var searchHitCount = 0
        var searchErrors = 0
        var searchQueriesWithHits = 0
        var hydrateErrors = 0
        var loadErrors = 0

        Log.i(
            TAG,
            "provider START id=$id caps=${plugin.descriptor.capabilities.joinToString()} candidateLimit=$rawCandidateLimit queries=${searchQueries.size}"
        )

        val directDiscovery = plugin as? SeriesDiscoveryProvider
        if (SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities && directDiscovery != null) {
            val hits = try {
                directDiscovery.discoverSeries(canonical)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "provider $id DISCOVERY error=${t.javaClass.simpleName}:${t.message}")
                emptyList()
            }
            directHitCount = hits.size
            Log.i(TAG, "provider $id DISCOVERY hits=${hits.size}")
            hits.take(3).forEach { addCandidate(candidates, it, rawCandidateLimit) }
        } else if (SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities) {
            Log.w(TAG, "provider $id declares SERIES_DISCOVERY but is not SeriesDiscoveryProvider")
        }

        val search = plugin as? SeriesSearchProvider
        if (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities && search != null) {
            searchQueries.forEachIndexed queryLoop@ { index, query ->
                if (candidates.size >= rawCandidateLimit) return@queryLoop
                val hits = try {
                    search.searchSeries(query)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    searchErrors++
                    Log.e(TAG, "provider $id SEARCH q=${index + 1}/${searchQueries.size} '${query.title.take(100)}' error=${t.javaClass.simpleName}:${t.message}")
                    return@queryLoop
                }
                searchHitCount += hits.size
                if (hits.isNotEmpty()) {
                    searchQueriesWithHits++
                    Log.i(
                        TAG,
                        "provider $id SEARCH q=${index + 1}/${searchQueries.size} '${query.title.take(100)}' hits=${hits.size} first=${hits.firstOrNull()?.series?.url}"
                    )
                }
                hits.take(2).forEach { addCandidate(candidates, it, rawCandidateLimit) }
            }
        } else if (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities) {
            Log.w(TAG, "provider $id declares SERIES_SEARCH but is not SeriesSearchProvider")
        }

        Log.i(
            TAG,
            "provider $id CANDIDATES unique=${candidates.size} directHits=$directHitCount searchHits=$searchHitCount queriesWithHits=$searchQueriesWithHits searchErrors=$searchErrors"
        )

        val findings = mutableListOf<SeriesDiscoveryFinding>()
        candidates.forEachIndexed candidateLoop@ { candidateIndex, candidate ->
            if (candidate.series.sourceId != id) {
                Log.w(TAG, "provider $id candidate ${candidateIndex + 1}: wrong sourceId=${candidate.series.sourceId} url=${candidate.series.url}")
                return@candidateLoop
            }
            val provider = plugin as? SeriesProvider
            val hydrated = if (provider != null) {
                try {
                    provider.resolveSeries(candidate.series.url) ?: candidate.series
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    hydrateErrors++
                    Log.e(TAG, "provider $id candidate ${candidateIndex + 1} RESOLVE error=${t.javaClass.simpleName}:${t.message} url=${candidate.series.url}")
                    candidate.series
                }
            } else candidate.series
            if (hydrated.sourceId != id) {
                Log.w(TAG, "provider $id candidate ${candidateIndex + 1}: hydrated wrong sourceId=${hydrated.sourceId}")
                return@candidateLoop
            }

            val books = if (provider != null) {
                try {
                    provider.loadSeriesBooks(hydrated)
                        .filter { it.sourceId == id }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    loadErrors++
                    Log.e(TAG, "provider $id candidate ${candidateIndex + 1} LOAD_BOOKS error=${t.javaClass.simpleName}:${t.message} url=${hydrated.url}")
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
                    SeriesDiscoveryFinding(
                        sourceId = id,
                        series = hydrated,
                        books = books,
                        confidence = overlapMatch.first,
                        disposition = MatchDisposition.AUTO_ACCEPT,
                        evidence = overlapMatch.second
                    )
                }
                seriesMatch != null && seriesMatch.disposition != MatchDisposition.REJECT -> {
                    SeriesDiscoveryFinding(
                        sourceId = id,
                        series = hydrated,
                        books = books,
                        confidence = seriesMatch.confidence,
                        disposition = seriesMatch.disposition,
                        evidence = seriesMatch.evidence
                    )
                }
                else -> null
            }

            val decision = accepted?.let { "${it.disposition}/${"%.3f".format(it.confidence)}" }
                ?: seriesMatch?.let { "REJECT/${it.disposition}/${"%.3f".format(it.confidence)}" }
                ?: "REJECT/no-match"
            Log.i(
                TAG,
                "provider $id candidate ${candidateIndex + 1}/${candidates.size} title='${hydrated.title}' books=${books.size} decision=$decision overlap=${overlapMatch?.second?.joinToString(" | ").orEmpty()} seriesEvidence=${seriesMatch?.evidence?.joinToString(" | ").orEmpty()} url=${hydrated.url}"
            )

            if (accepted != null) findings += accepted
        }

        val result = findings
            .sortedByDescending { it.confidence }
            .take(maxCandidatesPerSource)
        Log.i(
            TAG,
            "provider END id=$id findings=${result.size} hydrateErrors=$hydrateErrors loadErrors=$loadErrors candidates=${candidates.size}"
        )
        return result
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
