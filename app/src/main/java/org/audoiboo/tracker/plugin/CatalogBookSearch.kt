package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

class CatalogBookSearchEngine(
    private val registry: SourcePluginRegistry,
    private val maxResultsPerProvider: Int = 20,
    private val maxResults: Int = 40
) {
    init {
        require(maxResultsPerProvider in 1..50)
        require(maxResults in 1..100)
    }

    suspend fun search(query: String): List<CatalogBookSearchHit> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val hits: List<CatalogBookSearchHit> = supervisorScope {
            val tasks = mutableListOf<Deferred<List<CatalogBookSearchHit>>>()
            for (plugin in registry.withCapability(SourceCapability.BOOK_SEARCH)) {
                val provider = plugin as? CatalogBookSearchProvider ?: continue
                tasks += async {
                    try {
                        provider.searchBooks(clean, maxResultsPerProvider)
                            .filter { it.book.providerId == plugin.descriptor.id }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        emptyList<CatalogBookSearchHit>()
                    }
                }
            }
            tasks.awaitAll().flatten()
        }
        return deduplicate(hits).take(maxResults)
    }

    internal fun deduplicate(hits: List<CatalogBookSearchHit>): List<CatalogBookSearchHit> {
        val grouped: Map<String, List<CatalogBookSearchHit>> = hits.groupBy { hit ->
            val title = SourceIdentityMatcher.normalizeTitle(hit.book.title)
            val author = hit.book.authors.firstOrNull()?.let(SourceIdentityMatcher::normalizeAuthor).orEmpty()
            "$title|$author"
        }
        return grouped.values
            .mapNotNull { group -> group.maxWithOrNull(hitComparator) }
            .sortedWith(hitComparator.reversed())
    }

    private val hitComparator = compareBy<CatalogBookSearchHit> { it.confidence }
        .thenBy { it.book.seriesTitles.isNotEmpty() }
        .thenBy { it.book.seriesNumber != null }
        .thenBy { it.book.coverUrl != null }
        .thenBy { it.book.firstPublishYear != null }
}

internal fun catalogTitleConfidence(query: String, candidate: String): Float {
    val expected = SourceIdentityMatcher.normalizeTitle(query)
    val actual = SourceIdentityMatcher.normalizeTitle(candidate)
    if (expected.isBlank() || actual.isBlank()) return 0f
    if (expected == actual) return 1f
    if (actual.contains(expected) || expected.contains(actual)) return 0.86f
    val wanted = expected.split(' ').filter(String::isNotBlank).toSet()
    val found = actual.split(' ').filter(String::isNotBlank).toSet()
    if (wanted.isEmpty() || found.isEmpty()) return 0f
    val overlap = wanted.intersect(found).size.toFloat() / wanted.size.toFloat()
    return when {
        overlap >= 0.8f -> 0.76f
        overlap >= 0.6f -> 0.66f
        else -> 0f
    }
}
