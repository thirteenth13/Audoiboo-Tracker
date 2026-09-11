package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A normalized catalog series assembled from bibliographic providers before audio-source matching. */
data class CatalogSeries(
    val title: String,
    val authors: List<String>,
    val books: List<CatalogBook>
)

data class CatalogDiscoveryResult(
    val providerId: String,
    val author: CatalogAuthor,
    val series: List<CatalogSeries>,
    val standaloneBooks: List<CatalogBook>
)

data class InferredSeries(
    val title: String,
    val number: Double?
)

/** Conservative fallback for catalog records that do not expose an explicit series field. */
object CatalogSeriesHeuristics {
    private val labeledSuffix = Regex(
        "^(.+?)[\\s:,._\\-–—]*(?:книга|кн|том|часть|частина|book|volume|vol)\\.?\\s*#?([0-9]{1,3}(?:[.,][0-9]+)?)$",
        RegexOption.IGNORE_CASE
    )
    private val numericSuffix = Regex(
        "^(.+?)[\\s:,._\\-–—]+#?([0-9]{1,3}(?:[.,][0-9]+)?)$",
        RegexOption.IGNORE_CASE
    )
    private val ordinalToken = Regex("^#?[0-9]{1,3}$")

    fun infer(title: String): InferredSeries? {
        val cleaned = title.trim().replace(Regex("\\s+"), " ")
        val match = labeledSuffix.matchEntire(cleaned) ?: numericSuffix.matchEntire(cleaned) ?: return null
        val base = match.groupValues[1].trim(' ', ':', ',', '.', '_', '-', '–', '—')
        if (base.length < 3 || base.all(Char::isDigit)) return null
        val number = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return InferredSeries(base, number)
    }

    /**
     * Returns a conservative logical identity for one book inside a known series. Catalog providers
     * sometimes expose both a short work title ("Инкарнатор") and a bibliography-style title
     * ("Прокофьев Роман - Стеллар 01. Инкарнатор"). Normal title equality cannot collapse those.
     * If the normalized series title occurs near the front, strip everything through it and an
     * optional leading volume number. Otherwise keep the ordinary normalized title unchanged.
     */
    fun logicalBookKey(title: String, seriesTitle: String): String {
        val normalized = SourceIdentityMatcher.normalizeTitle(title)
        val series = SourceIdentityMatcher.normalizeTitle(seriesTitle)
        if (normalized.isBlank() || series.isBlank()) return normalized

        val titleTokens = normalized.split(' ').filter(String::isNotBlank)
        val seriesTokens = series.split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size <= seriesTokens.size) return normalized

        val maxStart = minOf(8, titleTokens.size - seriesTokens.size)
        val start = (0..maxStart).firstOrNull { index ->
            titleTokens.subList(index, index + seriesTokens.size) == seriesTokens
        } ?: return normalized

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
            if (title == null) {
                standalone += book
                return@forEach
            }
            val key = SourceIdentityMatcher.normalizeTitle(title)
            if (key.isBlank()) {
                standalone += book
                return@forEach
            }
            displayTitles.putIfAbsent(key, title)
            grouped.getOrPut(key) { mutableListOf() } += if (book.seriesNumber == null && inferred?.number != null) {
                book.copy(seriesNumber = inferred.number)
            } else book
        }

        val series = grouped.map { (key, rawBooks) ->
            val displayTitle = displayTitles.getValue(key)
            val books = deduplicateLogicalBooks(rawBooks, displayTitle)
            CatalogSeries(
                title = displayTitle,
                authors = books.flatMap { it.authors }.distinct(),
                books = books.sortedWith(
                    compareBy<CatalogBook> { it.seriesNumber ?: Double.MAX_VALUE }
                        .thenBy { it.firstPublishYear ?: Int.MAX_VALUE }
                        .thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }
                )
            )
        }.sortedBy { SourceIdentityMatcher.normalizeTitle(it.title) }

        return CatalogDiscoveryResult(
            providerId = catalog.author.providerId,
            author = catalog.author,
            series = series,
            standaloneBooks = deduplicateStandaloneBooks(standalone).sortedWith(
                compareBy<CatalogBook> { it.firstPublishYear ?: Int.MAX_VALUE }
                    .thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }
            )
        )
    }

    private fun deduplicateLogicalBooks(books: List<CatalogBook>, seriesTitle: String): List<CatalogBook> =
        books.groupBy {
            logicalBookKey(it.title, seriesTitle).ifBlank { "remote:${it.remoteId}" }
        }.values.map { duplicates ->
            duplicates.maxWithOrNull(compareBy<CatalogBook> { catalogBookRichness(it) }.thenBy { it.remoteId })!!
        }

    private fun deduplicateStandaloneBooks(books: List<CatalogBook>): List<CatalogBook> =
        books.groupBy { book ->
            val title = SourceIdentityMatcher.normalizeTitle(book.title).ifBlank { "remote:${book.remoteId}" }
            val authors = book.authors.map(SourceIdentityMatcher::normalizeAuthor).filter(String::isNotBlank).sorted().joinToString("|")
            "$title|$authors"
        }.values.map { duplicates ->
            duplicates.maxWithOrNull(compareBy<CatalogBook> { catalogBookRichness(it) }.thenBy { it.remoteId })!!
        }

    private fun catalogBookRichness(book: CatalogBook): Int =
        (if (!book.coverUrl.isNullOrBlank()) 8 else 0) +
            (if (book.seriesNumber != null) 4 else 0) +
            (if (book.firstPublishYear != null) 2 else 0) +
            (if (book.authors.isNotEmpty()) 1 else 0)
}

private data class CatalogProviderAttempt(
    val providerId: String,
    val results: List<CatalogDiscoveryResult>,
    val failed: Boolean
)

/** Searches all enabled bibliographic providers and returns normalized author/series catalogs. */
class CatalogDiscoveryEngine(
    private val registry: SourcePluginRegistry,
    private val maxAuthorsPerProvider: Int = 3,
    private val maxBooksPerAuthor: Int = 200,
    private val providerTimeoutMs: Long = 22_000L
) {
    init {
        require(maxAuthorsPerProvider in 1..10)
        require(maxBooksPerAuthor in 1..500)
        require(providerTimeoutMs in 1_000L..60_000L)
    }

    suspend fun discoverByAuthor(authorQuery: String): List<CatalogDiscoveryResult> {
        if (authorQuery.isBlank()) return emptyList()
        val attempts = supervisorScope {
            registry.withCapability(SourceCapability.AUTHOR_CATALOG).mapNotNull { plugin ->
                val provider = plugin as? AuthorCatalogProvider ?: return@mapNotNull null
                async {
                    val completed = withTimeoutOrNull(providerTimeoutMs) {
                        try {
                            CatalogProviderAttempt(
                                providerId = plugin.descriptor.id,
                                results = discoverProvider(plugin, provider, authorQuery),
                                failed = false
                            )
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            CatalogProviderAttempt(plugin.descriptor.id, emptyList(), failed = true)
                        }
                    }
                    completed ?: CatalogProviderAttempt(plugin.descriptor.id, emptyList(), failed = true)
                }
            }.awaitAll()
        }
        val results = attempts.flatMap { it.results }
        if (results.isEmpty() && attempts.isNotEmpty() && attempts.all { it.failed }) {
            val providers = attempts.joinToString { it.providerId }
            error("Каталог тимчасово недоступний ($providers). Перевірте мережу та повторіть пошук.")
        }
        return results.sortedWith(
            compareByDescending<CatalogDiscoveryResult> { it.author.confidence }
                .thenByDescending { it.series.size }
                .thenBy { it.author.name.lowercase() }
        )
    }

    private suspend fun discoverProvider(
        plugin: SourcePlugin,
        provider: AuthorCatalogProvider,
        authorQuery: String
    ): List<CatalogDiscoveryResult> {
        val authors = withContext(Dispatchers.IO) {
            provider.searchAuthors(authorQuery, maxAuthorsPerProvider)
        }
        if (authors.isEmpty()) return emptyList()

        var loadFailure: Throwable? = null
        var successfulLoads = 0
        val discovered = supervisorScope {
            authors.take(maxAuthorsPerProvider).mapNotNull { author ->
                if (author.providerId != plugin.descriptor.id) return@mapNotNull null
                async {
                    try {
                        val catalog = withContext(Dispatchers.IO) {
                            provider.loadAuthorCatalog(author, maxBooksPerAuthor)
                        }
                        successfulLoads++
                        CatalogSeriesHeuristics.group(catalog)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        loadFailure = t
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        if (discovered.isEmpty() && successfulLoads == 0 && loadFailure != null) throw loadFailure as Throwable
        return discovered
    }
}
