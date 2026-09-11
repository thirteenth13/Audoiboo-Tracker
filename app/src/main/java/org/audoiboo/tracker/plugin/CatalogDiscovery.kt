package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CatalogSeries(val title: String, val authors: List<String>, val books: List<CatalogBook>)
data class CatalogDiscoveryResult(val providerId: String, val author: CatalogAuthor, val series: List<CatalogSeries>, val standaloneBooks: List<CatalogBook>)
data class InferredSeries(val title: String, val number: Double?)

object CatalogSeriesHeuristics {
    private val labeledSuffix = Regex("^(.+?)[\\s:,._\\-–—]*(?:книга|кн|том|часть|частина|book|volume|vol)\\.?\\s*#?([0-9]{1,3}(?:[.,][0-9]+)?)$", RegexOption.IGNORE_CASE)
    private val numericSuffix = Regex("^(.+?)[\\s:,._\\-–—]+#?([0-9]{1,3}(?:[.,][0-9]+)?)$", RegexOption.IGNORE_CASE)
    private val ordinalToken = Regex("^#?[0-9]{1,3}$")

    fun infer(title: String): InferredSeries? {
        val cleaned = title.trim().replace(Regex("\\s+"), " ")
        val match = labeledSuffix.matchEntire(cleaned) ?: numericSuffix.matchEntire(cleaned) ?: return null
        val base = match.groupValues[1].trim(' ', ':', ',', '.', '_', '-', '–', '—')
        if (base.length < 3 || base.all(Char::isDigit)) return null
        val number = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return InferredSeries(base, number)
    }

    fun logicalBookKey(title: String, seriesTitle: String): String {
        val normalized = SourceIdentityMatcher.normalizeTitle(title)
        val series = SourceIdentityMatcher.normalizeTitle(seriesTitle)
        if (normalized.isBlank() || series.isBlank()) return normalized
        val titleTokens = normalized.split(' ').filter(String::isNotBlank)
        val seriesTokens = series.split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size <= seriesTokens.size) return normalized
        val maxStart = minOf(8, titleTokens.size - seriesTokens.size)
        val start = (0..maxStart).firstOrNull { index -> titleTokens.subList(index, index + seriesTokens.size) == seriesTokens } ?: return normalized
        var tail = titleTokens.drop(start + seriesTokens.size)
        if (tail.firstOrNull()?.matches(ordinalToken) == true) tail = tail.drop(1)
        if (tail.firstOrNull() in setOf("книга", "кн", "том", "часть", "частина", "book", "volume", "vol")) {
            tail = tail.drop(1)
            if (tail.firstOrNull()?.matches(ordinalToken) == true) tail = tail.drop(1)
        }
        return tail.joinToString(" ").ifBlank { normalized }
    }

    fun group(catalog: AuthorCatalog): CatalogDiscoveryResult {
        val grouped = linkedMapOf<String, MutableList<CatalogBook>>()
        val displayTitles = linkedMapOf<String, String>()
        val standalone = mutableListOf<CatalogBook>()
        catalog.books.forEach { book ->
            val explicit = book.seriesTitles.firstOrNull { it.isNotBlank() }?.trim()
            val inferred = infer(book.title)
            val title = explicit ?: inferred?.title
            if (title == null) { standalone += book; return@forEach }
            val key = SourceIdentityMatcher.normalizeTitle(title)
            if (key.isBlank()) { standalone += book; return@forEach }
            displayTitles.putIfAbsent(key, title)
            grouped.getOrPut(key) { mutableListOf() } += if (book.seriesNumber == null && inferred?.number != null) book.copy(seriesNumber = inferred.number) else book
        }
        val series = grouped.map { (key, rawBooks) ->
            val displayTitle = displayTitles.getValue(key)
            val books = deduplicateLogicalBooks(rawBooks, displayTitle)
            CatalogSeries(displayTitle, books.flatMap { it.authors }.distinct(), books.sortedWith(compareBy<CatalogBook> { it.seriesNumber ?: Double.MAX_VALUE }.thenBy { it.firstPublishYear ?: Int.MAX_VALUE }.thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }))
        }.sortedBy { SourceIdentityMatcher.normalizeTitle(it.title) }
        return CatalogDiscoveryResult(catalog.author.providerId, catalog.author, series, deduplicateStandaloneBooks(standalone).sortedWith(compareBy<CatalogBook> { it.firstPublishYear ?: Int.MAX_VALUE }.thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }))
    }

    private fun deduplicateLogicalBooks(books: List<CatalogBook>, seriesTitle: String): List<CatalogBook> = books.groupBy { logicalBookKey(it.title, seriesTitle).ifBlank { "remote:${it.remoteId}" } }.values.map { it.maxWithOrNull(compareBy<CatalogBook> { b -> catalogBookRichness(b) }.thenBy { b -> b.remoteId })!! }
    private fun deduplicateStandaloneBooks(books: List<CatalogBook>): List<CatalogBook> = books.groupBy { book -> "${SourceIdentityMatcher.normalizeTitle(book.title).ifBlank { "remote:${book.remoteId}" }}|${book.authors.map(SourceIdentityMatcher::normalizeAuthor).filter(String::isNotBlank).sorted().joinToString("|")}" }.values.map { it.maxWithOrNull(compareBy<CatalogBook> { b -> catalogBookRichness(b) }.thenBy { b -> b.remoteId })!! }
    private fun catalogBookRichness(book: CatalogBook): Int = (if (!book.coverUrl.isNullOrBlank()) 8 else 0) + (if (book.seriesNumber != null) 4 else 0) + (if (book.firstPublishYear != null) 2 else 0) + (if (book.authors.isNotEmpty()) 1 else 0)
}

class CatalogDiscoveryEngine(
    private val registry: SourcePluginRegistry,
    private val maxAuthorsPerProvider: Int = 3,
    private val maxBooksPerAuthor: Int = 200,
    private val providerTimeoutMs: Long = 7_000L
) {
    init { require(maxAuthorsPerProvider in 1..10); require(maxBooksPerAuthor in 1..500); require(providerTimeoutMs in 1_000L..60_000L) }

    suspend fun discoverByAuthor(authorQuery: String): List<CatalogDiscoveryResult> {
        if (authorQuery.isBlank()) return emptyList()
        SeriesDiagnosticLog.i("CATALOG AUTHOR START query='$authorQuery' stageTimeout=${providerTimeoutMs}ms")
        val results = supervisorScope {
            registry.withCapability(SourceCapability.AUTHOR_CATALOG).mapNotNull { plugin ->
                val provider = plugin as? AuthorCatalogProvider ?: return@mapNotNull null
                async { discoverProvider(plugin, provider, authorQuery) }
            }.awaitAll().flatten()
        }
        SeriesDiagnosticLog.i("CATALOG AUTHOR END query='$authorQuery' results=${results.size} providers=${results.groupingBy { it.providerId }.eachCount()}")
        return results.sortedWith(compareByDescending<CatalogDiscoveryResult> { it.author.confidence }.thenByDescending { it.series.size }.thenBy { it.author.name.lowercase() })
    }

    private suspend fun discoverProvider(plugin: SourcePlugin, provider: AuthorCatalogProvider, authorQuery: String): List<CatalogDiscoveryResult> {
        SeriesDiagnosticLog.i("CATALOG AUTHOR SEARCH provider=${plugin.descriptor.id} query='$authorQuery' limit=$maxAuthorsPerProvider")
        val authors = try {
            val result = withTimeoutOrNull(providerTimeoutMs) {
                withContext(Dispatchers.IO) { provider.searchAuthors(authorQuery, maxAuthorsPerProvider) }
            }
            if (result == null) {
                SeriesDiagnosticLog.w("CATALOG AUTHOR SEARCH TIMEOUT provider=${plugin.descriptor.id} query='$authorQuery' after=${providerTimeoutMs}ms")
                return emptyList()
            }
            result
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            SeriesDiagnosticLog.e("CATALOG AUTHOR SEARCH FAILED provider=${plugin.descriptor.id} query='$authorQuery'", t)
            return emptyList()
        }
        SeriesDiagnosticLog.i("CATALOG AUTHOR SEARCH RESULT provider=${plugin.descriptor.id} query='$authorQuery' count=${authors.size} authors=${authors.joinToString { "${it.name}[id=${it.remoteId},conf=${"%.2f".format(it.confidence)}]" }}")
        return supervisorScope {
            authors.take(maxAuthorsPerProvider).mapNotNull { author ->
                if (author.providerId != plugin.descriptor.id) return@mapNotNull null
                async {
                    try {
                        SeriesDiagnosticLog.i("CATALOG AUTHOR LOAD provider=${plugin.descriptor.id} id=${author.remoteId} name='${author.name}' limit=$maxBooksPerAuthor")
                        val catalog = withTimeoutOrNull(providerTimeoutMs) {
                            withContext(Dispatchers.IO) { provider.loadAuthorCatalog(author, maxBooksPerAuthor) }
                        }
                        if (catalog == null) {
                            SeriesDiagnosticLog.w("CATALOG AUTHOR LOAD TIMEOUT provider=${plugin.descriptor.id} id=${author.remoteId} name='${author.name}' after=${providerTimeoutMs}ms")
                            return@async null
                        }
                        SeriesDiagnosticLog.i("CATALOG AUTHOR BOOKS provider=${plugin.descriptor.id} id=${author.remoteId} name='${author.name}' books=${catalog.books.size}")
                        val grouped = CatalogSeriesHeuristics.group(catalog)
                        SeriesDiagnosticLog.i("CATALOG AUTHOR GROUPED provider=${plugin.descriptor.id} id=${author.remoteId} series=${grouped.series.size} standalone=${grouped.standaloneBooks.size} seriesNames=${grouped.series.take(20).joinToString { "'${it.title}'(${it.books.size})" }}")
                        grouped
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        SeriesDiagnosticLog.e("CATALOG AUTHOR LOAD FAILED provider=${plugin.descriptor.id} id=${author.remoteId} name='${author.name}'", t)
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
