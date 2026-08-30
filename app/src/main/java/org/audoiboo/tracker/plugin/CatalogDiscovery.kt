package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException

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
    private val numberedSuffix = Regex(
        pattern = """^(.+?)[\\s:,.\\-–—]*(?:(?:книга|кн|том|часть|частина|book|volume|vol)\\.?\\s*)?#?([0-9]{1,3}(?:[.,][0-9]+)?)$""",
        option = RegexOption.IGNORE_CASE
    )

    fun infer(title: String): InferredSeries? {
        val cleaned = title.trim().replace(Regex("\\s+"), " ")
        val match = numberedSuffix.matchEntire(cleaned) ?: return null
        val base = match.groupValues[1].trim(' ', ':', ',', '.', '-', '–', '—')
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
        val results = mutableListOf<CatalogDiscoveryResult>()
        registry.withCapability(SourceCapability.AUTHOR_CATALOG).forEach pluginLoop@ { plugin ->
            val provider = plugin as? AuthorCatalogProvider ?: return@pluginLoop
            val authors = try {
                provider.searchAuthors(authorQuery, maxAuthorsPerProvider)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                return@pluginLoop
            }
            authors.take(maxAuthorsPerProvider).forEach authorLoop@ { author ->
                if (author.providerId != plugin.descriptor.id) return@authorLoop
                val catalog = try {
                    provider.loadAuthorCatalog(author, maxBooksPerAuthor)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    return@authorLoop
                }
                results += CatalogSeriesHeuristics.group(catalog)
            }
        }
        return results.sortedWith(
            compareByDescending<CatalogDiscoveryResult> { it.author.confidence }
                .thenByDescending { it.series.size }
                .thenBy { it.author.name.lowercase() }
        )
    }
}
