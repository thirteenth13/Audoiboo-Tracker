package org.audoiboo.tracker.plugin.flibusta

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.audoiboo.tracker.plugin.BookProvider
import org.audoiboo.tracker.plugin.CanonicalSeriesMatchInput
import org.audoiboo.tracker.plugin.SeriesCandidate
import org.audoiboo.tracker.plugin.SeriesDiscoveryProvider
import org.audoiboo.tracker.plugin.SeriesSearchProvider
import org.audoiboo.tracker.plugin.SeriesSearchQuery
import org.audoiboo.tracker.plugin.SourceAuthor
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceBookRef
import org.audoiboo.tracker.plugin.SourceCapability
import org.audoiboo.tracker.plugin.SourceDescriptor
import org.audoiboo.tracker.plugin.SourcePlugin
import org.audoiboo.tracker.plugin.SourceSeries

/** Makes the existing Flibusta parsers visible to the common source-discovery pipeline. */
class FlibustaSourcePlugin(
    private val transport: FlibustaTransport = HttpUrlConnectionFlibustaTransport(),
) : SourcePlugin, SeriesSearchProvider, SeriesDiscoveryProvider, BookProvider {

    override val descriptor = SourceDescriptor(
        id = ID,
        name = "Flibusta",
        version = 1,
        hosts = HOSTS,
        capabilities = setOf(
            SourceCapability.BOOK_LOOKUP,
            SourceCapability.SERIES_SEARCH,
            SourceCapability.SERIES_DISCOVERY,
        ),
    )

    override fun supports(url: String): Boolean = FlibustaParserRegistry.forUrl(url) != null

    override suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate> =
        searchCatalog(query.title).toCandidates(query.title)

    override suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
        val searches = buildList {
            canonical.books.take(8).forEach { book ->
                val author = (book.authors + canonical.authors).firstOrNull { it.isNotBlank() }
                add(listOfNotNull(author, book.title.takeIf { it.isNotBlank() }).joinToString(" "))
            }
            add(canonical.title)
        }.map(String::trim).filter(String::isNotBlank).distinct().take(10)

        val entries = linkedMapOf<String, FlibustaCatalogEntry>()
        for (query in searches) {
            searchCatalog(query).forEach { entry -> entries.putIfAbsent(normalizeUrl(entry.url), entry) }
        }

        val expanded = expandMatchingSeries(entries.values.toList(), canonical.title)
        return expanded.toCandidates(canonical.title)
    }

    override suspend fun loadBook(url: String): SourceBook? {
        val page = fetchBookPage(url) ?: return null
        val title = page.title?.takeIf(String::isNotBlank) ?: return null
        return SourceBook(
            sourceId = ID,
            remoteId = page.remoteId,
            url = page.canonicalUrl,
            title = title,
            authors = page.author?.takeIf(String::isNotBlank)?.let { listOf(SourceAuthor(it, page.authorUrl)) }.orEmpty(),
            seriesTitle = page.series,
            seriesNumber = page.seriesNumber?.toDouble(),
            coverUrl = page.coverUrl,
        )
    }

    private fun fetchBookPage(url: String): FlibustaBookPage? {
        val parser = FlibustaParserRegistry.forUrl(url) ?: return null
        val response = runCatching { transport.get(url, requestHeaders()) }.getOrNull() ?: return null
        if (response.statusCode !in 200..299) return null
        return runCatching {
            parser.parseBookPage(response.body.toString(Charsets.UTF_8), response.finalUrl)
        }.getOrNull()
    }

    /**
     * Search pages can expose only a matching book link. Hydrate a few such books, follow their
     * explicit series link and parse that page so discovery gets real book overlap instead of a
     * title-only candidate with books=0.
     */
    private fun expandMatchingSeries(
        entries: List<FlibustaCatalogEntry>,
        canonicalSeriesTitle: String,
    ): List<FlibustaCatalogEntry> {
        if (entries.isEmpty()) return entries
        val output = linkedMapOf<String, FlibustaCatalogEntry>()
        entries.forEach { output.putIfAbsent(normalizeUrl(it.url), it) }
        val visitedSeries = linkedSetOf<String>()

        entries.asSequence().take(MAX_SERIES_PROBES).forEach { entry ->
            val page = fetchBookPage(entry.url) ?: return@forEach
            val seriesTitle = page.series?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
            if (!seriesTitle.equals(canonicalSeriesTitle.trim(), ignoreCase = true)) return@forEach
            val seriesUrl = page.seriesUrl?.takeIf(String::isNotBlank) ?: return@forEach
            val normalizedSeriesUrl = normalizeUrl(seriesUrl)
            if (!visitedSeries.add(normalizedSeriesUrl)) return@forEach
            fetchCatalogPage(seriesUrl).forEach { seriesEntry ->
                output.putIfAbsent(normalizeUrl(seriesEntry.url), seriesEntry)
            }
        }
        return output.values.toList()
    }

    private fun fetchCatalogPage(url: String): List<FlibustaCatalogEntry> {
        val parser = FlibustaParserRegistry.forUrl(url) ?: return emptyList()
        val response = runCatching { transport.get(url, requestHeaders()) }.getOrNull() ?: return emptyList()
        if (response.statusCode !in 200..299) return emptyList()
        return runCatching {
            parser.parseCatalog(response.body.toString(Charsets.UTF_8), response.finalUrl)
        }.getOrDefault(emptyList()).take(MAX_SEARCH_RESULTS)
    }

    private fun searchCatalog(query: String): List<FlibustaCatalogEntry> {
        if (query.isBlank()) return emptyList()
        for (variant in SEARCH_ORDER) {
            val url = searchUrl(variant, query)
            val response = runCatching { transport.get(url, requestHeaders()) }.getOrNull() ?: continue
            if (response.statusCode !in 200..299) continue
            val parser = FlibustaParserRegistry.forUrl(response.finalUrl) ?: FlibustaParserRegistry.forUrl(url) ?: continue
            val entries = parser.parseCatalog(response.body.toString(Charsets.UTF_8), response.finalUrl)
            if (entries.isNotEmpty()) return entries.take(MAX_SEARCH_RESULTS)
        }
        return emptyList()
    }

    private fun List<FlibustaCatalogEntry>.toCandidates(fallbackSeriesTitle: String): List<SeriesCandidate> {
        if (isEmpty()) return emptyList()
        val grouped = groupBy { it.series?.trim()?.takeIf(String::isNotBlank) ?: fallbackSeriesTitle.trim() }
        return grouped.mapNotNull { (seriesTitle, entries) ->
            if (seriesTitle.isBlank()) return@mapNotNull null
            val first = entries.first()
            val authors = entries.mapNotNull { it.author?.trim()?.takeIf(String::isNotBlank) }
                .distinct().map(::SourceAuthor)
            val refs = entries.distinctBy { normalizeUrl(it.url) }.map { entry ->
                SourceBookRef(entry.remoteId, entry.url, entry.title, entry.seriesNumber?.toDouble())
            }
            SeriesCandidate(
                SourceSeries(
                    sourceId = ID,
                    remoteId = null,
                    url = first.url,
                    title = seriesTitle,
                    authors = authors,
                    books = refs,
                ),
                sourceScore = if (entries.any { !it.series.isNullOrBlank() }) 0.9f else 0.65f,
            )
        }
    }

    companion object {
        const val ID = "flibusta"
        val HOSTS = setOf("flibusta.site", "flibusta.one", "flibusta.name")
        private val SEARCH_ORDER = listOf(FlibustaVariant.SITE, FlibustaVariant.ONE, FlibustaVariant.NAME)
        private const val MAX_SEARCH_RESULTS = 40
        private const val MAX_SERIES_PROBES = 8

        internal fun searchUrl(variant: FlibustaVariant, query: String): String {
            val encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())
            return when (variant) {
                FlibustaVariant.SITE -> "https://${variant.host}/booksearch?ask=$encoded"
                FlibustaVariant.ONE, FlibustaVariant.NAME -> "https://${variant.host}/search/?q=$encoded"
            }
        }

        private fun requestHeaders() = mapOf(
            "Accept" to "text/html,application/xhtml+xml",
            "User-Agent" to "Audoiboo-Tracker/FlibustaDiscovery",
        )
    }
}

val FlibustaBuiltInSourcePlugin: SourcePlugin = FlibustaSourcePlugin()
