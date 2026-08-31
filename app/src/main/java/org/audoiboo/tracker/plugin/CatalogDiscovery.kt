package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

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

    fun infer(title: String): InferredSeries? {
        val cleaned = title.trim().replace(Regex("\\s+"), " ")
        val match = labeledSuffix.matchEntire(cleaned) ?: numericSuffix.matchEntire(cleaned) ?: return null
        val base = match.groupValues[1].trim(' ', ':', ',', '.', '_', '-', '–', '—')
        if (base.length < 3 || base.all(Char::isDigit)) return null
        val number = match.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        return InferredSeries(base, number)
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

        val series = grouped.map { (key, books) ->
            CatalogSeries(
                title = displayTitles.getValue(key),
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
            standaloneBooks = standalone.sortedWith(
                compareBy<CatalogBook> { it.firstPublishYear ?: Int.MAX_VALUE }
                    .thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }
            )
        )
    }
}

/** Searches all enabled bibliographic providers and returns normalized author/series catalogs. */
class CatalogDiscoveryEngine(
    private val registry: SourcePluginRegistry,
    private val maxAuthorsPerProvider: Int = 3,
    private val maxBooksPerAuthor: Int = 200
) {
    init {
        require(maxAuthorsPerProvider in 1..10)
        require(maxBooksPerAuthor in 1..500)
    }

    suspend fun discoverByAuthor(authorQuery: String): List<CatalogDiscoveryResult> {
        if (authorQuery.isBlank()) return emptyList()
        val results = supervisorScope {
            registry.withCapability(SourceCapability.AUTHOR_CATALOG).mapNotNull { plugin ->
                val provider = plugin as? AuthorCatalogProvider ?: return@mapNotNull null
                async {
                    discoverProvider(plugin, provider, authorQuery)
                }
            }.awaitAll().flatten()
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
        val authors = try {
            provider.searchAuthors(authorQuery, maxAuthorsPerProvider)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return emptyList()
        }
        return supervisorScope {
            authors.take(maxAuthorsPerProvider).mapNotNull { author ->
                if (author.providerId != plugin.descriptor.id) return@mapNotNull null
                async {
                    try {
                        CatalogSeriesHeuristics.group(provider.loadAuthorCatalog(author, maxBooksPerAuthor))
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}
