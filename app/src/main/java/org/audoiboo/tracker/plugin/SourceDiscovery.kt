package org.audoiboo.tracker.plugin

import android.util.Log
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

data class SeriesDiscoveryFinding(
    val sourceId: String,
    val series: SourceSeries,
    val books: List<SourceBook>,
    val confidence: Float,
    val disposition: MatchDisposition,
    val evidence: List<String>
)

class SourceDiscoveryEngine(
    private val registry: SourcePluginRegistry,
    private val maxCandidatesPerSource: Int = 5
) {
    companion object {
        private const val TAG = "AudoibooSeries"
        private fun info(message: String) { Log.i(TAG, message); SeriesDiagnosticLog.i(message) }
        private fun warn(message: String) { Log.w(TAG, message); SeriesDiagnosticLog.w(message) }
        private fun error(message: String, t: Throwable? = null) { Log.e(TAG, message, t); SeriesDiagnosticLog.e(message, t) }
    }

    init { require(maxCandidatesPerSource in 1..20) }

    suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput, excludeSourceId: String? = null): List<SeriesDiscoveryFinding> {
        val baseQuery = SeriesSearchQuery(canonical.title, canonical.authors, canonical.books.map { KnownBook(it.title, it.number, it.authors) })
        val normalizedSeriesTitle = SourceIdentityMatcher.normalizeTitle(canonical.title)
        val authors = canonical.authors.map { it.trim() }.filter { it.isNotBlank() }.distinctBy(SourceIdentityMatcher::normalizeAuthor).take(3)
        val targetedBookQueries = canonical.books.sortedBy { it.number ?: Double.MAX_VALUE }.flatMap { book ->
            val title = book.title.trim(); if (title.isBlank()) return@flatMap emptyList()
            val bookAuthors = (book.authors + authors).map { it.trim() }.filter { it.isNotBlank() }.distinctBy(SourceIdentityMatcher::normalizeAuthor).take(2)
            buildList { bookAuthors.forEach { add(baseQuery.copy(title = "$it $title")) }; add(baseQuery.copy(title = title)) }
        }
        val fallbackQueries = buildList { add(baseQuery); authors.forEach { add(baseQuery.copy(title = it)) } }
        val searchQueries = (targetedBookQueries + fallbackQueries).distinctBy { SourceIdentityMatcher.normalizeTitle(it.title) }
            .filter { SourceIdentityMatcher.normalizeTitle(it.title) != normalizedSeriesTitle || it.title == canonical.title }
        val providers = registry.plugins.filterNot { it.descriptor.id == excludeSourceId }.filter {
            SourceCapability.SERIES_SEARCH in it.descriptor.capabilities || SourceCapability.SERIES_DISCOVERY in it.descriptor.capabilities
        }
        info("discovery START canonical='${canonical.title}' id=${canonical.id} books=${canonical.books.size} authors=${authors.joinToString()} queries=${searchQueries.size} exclude=$excludeSourceId providers=${providers.joinToString { it.descriptor.id }}")
        val findings = supervisorScope { providers.map { plugin -> async { discoverSource(plugin, canonical, searchQueries) } }.awaitAll().flatten() }
        val result = findings.distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.series.url) }.sortedWith(compareByDescending<SeriesDiscoveryFinding> { it.confidence }.thenBy { it.sourceId })
        info("discovery END canonical='${canonical.title}' findings=${result.size} bySource=${result.groupingBy { it.sourceId }.eachCount()}")
        return result
    }

    private suspend fun discoverSource(plugin: SourcePlugin, canonical: CanonicalSeriesMatchInput, searchQueries: List<SeriesSearchQuery>): List<SeriesDiscoveryFinding> {
        val id = plugin.descriptor.id
        val rawCandidateLimit = (canonical.books.size.coerceAtLeast(maxCandidatesPerSource) * 3).coerceIn(maxCandidatesPerSource * 3, 60)
        val candidates = mutableListOf<SeriesCandidate>()
        val matchedSearchBooks = linkedMapOf<String, Pair<SourceBook, CanonicalBookMatchInput>>()
        val inspectedBookUrls = hashSetOf<String>()
        var directHitCount = 0
        var searchHitCount = 0
        var searchErrors = 0
        var searchQueriesWithHits = 0
        var hydrateErrors = 0
        var loadErrors = 0
        var bookLookupErrors = 0
        var lastFingerprint: String? = null
        var repeatedFingerprintCount = 0
        info("provider START id=$id caps=${plugin.descriptor.capabilities.joinToString()} candidateLimit=$rawCandidateLimit queries=${searchQueries.size}")

        val directDiscovery = plugin as? SeriesDiscoveryProvider
        if (SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities && directDiscovery != null) {
            val hits = try { directDiscovery.discoverSeries(canonical) } catch (t: Throwable) {
                if (t is CancellationException) throw t
                error("provider $id DISCOVERY error=${t.javaClass.simpleName}:${t.message}", t)
                emptyList()
            }
            directHitCount = hits.size
            info("provider $id DISCOVERY hits=${hits.size}")
            hits.take(3).forEach { addCandidate(candidates, it, rawCandidateLimit) }
        } else if (SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities) {
            warn("provider $id declares SERIES_DISCOVERY but is not SeriesDiscoveryProvider")
        }

        val search = plugin as? SeriesSearchProvider
        val bookProvider = plugin as? BookProvider
        val canResolveBookHits = bookProvider != null && SourceCapability.BOOK_LOOKUP in plugin.descriptor.capabilities
        if (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities && search != null) {
            for ((index, query) in searchQueries.withIndex()) {
                if (candidates.size >= rawCandidateLimit || matchedSearchBooks.size >= canonical.books.size) break
                val hits = try {
                    search.searchSeries(query)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    searchErrors++
                    error("provider $id SEARCH q=${index + 1}/${searchQueries.size} '${query.title.take(100)}' error=${t.javaClass.simpleName}:${t.message}", t)
                    continue
                }
                searchHitCount += hits.size
                if (hits.isNotEmpty()) {
                    searchQueriesWithHits++
                    info("provider $id SEARCH q=${index + 1}/${searchQueries.size} '${query.title.take(100)}' hits=${hits.size} first=${hits.firstOrNull()?.series?.url}")
                }

                val fingerprint = hits.take(4).joinToString("|") { SourceKeys.normalizeUrl(it.series.url) }
                if (fingerprint.isNotBlank() && fingerprint == lastFingerprint) repeatedFingerprintCount++
                else {
                    lastFingerprint = fingerprint.takeIf { it.isNotBlank() }
                    repeatedFingerprintCount = if (fingerprint.isBlank()) 0 else 1
                }

                hits.take(12).forEach { candidate ->
                    val url = candidate.series.url
                    val normalizedUrl = SourceKeys.normalizeUrl(url)
                    val bookUrl = if (canResolveBookHits) canonicalPluginBookUrl(id, url) else null
                    if (bookUrl != null) {
                        if (!inspectedBookUrls.add(normalizedUrl)) return@forEach
                        val resolvedBook = try {
                            bookProvider!!.loadBook(bookUrl)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            bookLookupErrors++
                            error("provider $id BOOK_SEARCH_LOOKUP error=${t.javaClass.simpleName}:${t.message} url=$bookUrl", t)
                            null
                        } ?: return@forEach
                        val match = SourceIdentityMatcher.bestBookMatch(resolvedBook, canonical.books)
                            ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                            ?: return@forEach
                        val key = match.value.id
                        if (key !in matchedSearchBooks) {
                            val normalizedBook = resolvedBook.copy(
                                seriesTitle = canonical.title,
                                seriesNumber = resolvedBook.seriesNumber ?: match.value.number
                            )
                            matchedSearchBooks[key] = normalizedBook to match.value
                            info("provider $id BOOK_MATCH canonical='${match.value.title}' source='${resolvedBook.title}' confidence=${"%.3f".format(match.confidence)} url=${resolvedBook.url}")
                        }
                    } else if (isPlausibleSeriesCandidate(id, url)) {
                        addCandidate(candidates, candidate, rawCandidateLimit)
                    }
                }

                if (repeatedFingerprintCount >= 4) {
                    info("provider $id SEARCH short-circuit repeated result fingerprint after ${index + 1} queries")
                    break
                }
            }
        } else if (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities) {
            warn("provider $id declares SERIES_SEARCH but is not SeriesSearchProvider")
        }

        info("provider $id CANDIDATES unique=${candidates.size} bookMatches=${matchedSearchBooks.size} inspectedBooks=${inspectedBookUrls.size} directHits=$directHitCount searchHits=$searchHitCount queriesWithHits=$searchQueriesWithHits searchErrors=$searchErrors")
        val findings = mutableListOf<SeriesDiscoveryFinding>()

        buildBookSearchFinding(id, canonical, matchedSearchBooks.values.map { it.first })?.let(findings::add)

        candidates.forEachIndexed candidateLoop@ { candidateIndex, candidate ->
            if (candidate.series.sourceId != id) {
                warn("provider $id candidate ${candidateIndex + 1}: wrong sourceId=${candidate.series.sourceId} url=${candidate.series.url}")
                return@candidateLoop
            }
            val provider = plugin as? SeriesProvider
            val hydrated = if (provider != null) try {
                provider.resolveSeries(candidate.series.url) ?: candidate.series
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                hydrateErrors++
                error("provider $id candidate ${candidateIndex + 1} RESOLVE error=${t.javaClass.simpleName}:${t.message} url=${candidate.series.url}", t)
                candidate.series
            } else candidate.series
            if (hydrated.sourceId != id) {
                warn("provider $id candidate ${candidateIndex + 1}: hydrated wrong sourceId=${hydrated.sourceId}")
                return@candidateLoop
            }
            val books = if (provider != null) try {
                provider.loadSeriesBooks(hydrated).filter { it.sourceId == id }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                loadErrors++
                error("provider $id candidate ${candidateIndex + 1} LOAD_BOOKS error=${t.javaClass.simpleName}:${t.message} url=${hydrated.url}", t)
                emptyList()
            } else emptyList()
            val seriesMatch = SourceIdentityMatcher.bestSeriesMatch(hydrated, books, listOf(canonical))
            val overlapMatch = matchByCanonicalBooks(books, canonical)
            val accepted = when {
                overlapMatch != null -> SeriesDiscoveryFinding(id, hydrated, books, overlapMatch.first, MatchDisposition.AUTO_ACCEPT, overlapMatch.second)
                seriesMatch != null && seriesMatch.disposition != MatchDisposition.REJECT -> SeriesDiscoveryFinding(id, hydrated, books, seriesMatch.confidence, seriesMatch.disposition, seriesMatch.evidence)
                else -> null
            }
            val decision = accepted?.let { "${it.disposition}/${"%.3f".format(it.confidence)}" } ?: seriesMatch?.let { "REJECT/${it.disposition}/${"%.3f".format(it.confidence)}" } ?: "REJECT/no-match"
            info("provider $id candidate ${candidateIndex + 1}/${candidates.size} title='${hydrated.title}' books=${books.size} decision=$decision overlap=${overlapMatch?.second?.joinToString(" | ").orEmpty()} seriesEvidence=${seriesMatch?.evidence?.joinToString(" | ").orEmpty()} url=${hydrated.url}")
            if (accepted != null) findings += accepted
        }
        val result = findings
            .distinctBy { finding -> finding.books.map { SourceKeys.normalizeUrl(it.url) }.sorted().joinToString("|") }
            .sortedByDescending { it.confidence }
            .take(maxCandidatesPerSource)
        info("provider END id=$id findings=${result.size} hydrateErrors=$hydrateErrors loadErrors=$loadErrors bookLookupErrors=$bookLookupErrors candidates=${candidates.size}")
        return result
    }

    private fun buildBookSearchFinding(
        sourceId: String,
        canonical: CanonicalSeriesMatchInput,
        books: List<SourceBook>
    ): SeriesDiscoveryFinding? {
        if (books.isEmpty()) return null
        val ordered = books.sortedWith(compareBy<SourceBook> { it.seriesNumber ?: Double.MAX_VALUE }.thenBy { it.title })
        val confidence = (0.95f + (ordered.size - 1).coerceAtLeast(0) * 0.01f).coerceAtMost(0.99f)
        val authors = ordered.flatMap { it.authors }.distinctBy { SourceIdentityMatcher.normalizeAuthor(it.name) }
        val series = SourceSeries(
            sourceId = sourceId,
            url = ordered.first().url,
            title = canonical.title,
            authors = authors,
            books = ordered.map { book -> SourceBookRef(book.remoteId, book.url, book.title, book.seriesNumber) }
        )
        return SeriesDiscoveryFinding(
            sourceId = sourceId,
            series = series,
            books = ordered,
            confidence = confidence,
            disposition = MatchDisposition.AUTO_ACCEPT,
            evidence = listOf("canonical book search matches: ${ordered.size}", "book pages accepted without requiring a provider series page")
        )
    }

    private fun matchByCanonicalBooks(books: List<SourceBook>, canonical: CanonicalSeriesMatchInput): Pair<Float, List<String>>? {
        if (books.isEmpty() || canonical.books.isEmpty()) return null
        val matchedCanonicalIds = linkedSetOf<String>(); var authorSupportedMatches = 0
        books.forEach { incoming ->
            val match = SourceIdentityMatcher.bestBookMatch(incoming, canonical.books)?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT } ?: return@forEach
            if (!matchedCanonicalIds.add(match.value.id)) return@forEach
            val incomingAuthors = incoming.authors.map { SourceIdentityMatcher.normalizeAuthor(it.name) }.filter { it.isNotBlank() }.toSet()
            val canonicalAuthors = match.value.authors.map(SourceIdentityMatcher::normalizeAuthor).filter { it.isNotBlank() }.toSet()
            if (incomingAuthors.isNotEmpty() && canonicalAuthors.isNotEmpty() && incomingAuthors.intersect(canonicalAuthors).isNotEmpty()) authorSupportedMatches++
        }
        val count = matchedCanonicalIds.size
        if (!(count >= 2 || (count == 1 && authorSupportedMatches == 1))) return null
        val confidence = (0.95f + (count - 1).coerceAtLeast(0) * 0.01f).coerceAtMost(0.99f)
        return confidence to buildList { add("canonical book overlap: $count"); if (authorSupportedMatches > 0) add("author-supported book matches: $authorSupportedMatches"); add("provider series title is not required to match canonical series title") }
    }

    private fun isPlausibleSeriesCandidate(pluginId: String, url: String): Boolean = runCatching {
        val path = URI(url.trim()).path.orEmpty()
        when (pluginId) {
            "baza-knig" -> path.startsWith("/series-")
            "izib" -> path.startsWith("/serie")
            "knigavuhe" -> path.startsWith("/series/") && path.length > "/series/".length
            "lis10book" -> path.startsWith("/serie/") && path.length > "/serie/".length
            "poleknig" -> path.startsWith("/series/") && path.length > "/series/".length
            else -> true
        }
    }.getOrDefault(false)

    private fun addCandidate(target: MutableList<SeriesCandidate>, candidate: SeriesCandidate, limit: Int = maxCandidatesPerSource) {
        if (target.size >= limit) return
        val normalized = SourceKeys.normalizeUrl(candidate.series.url)
        if (target.none { SourceKeys.normalizeUrl(it.series.url) == normalized }) target += candidate
    }
}
