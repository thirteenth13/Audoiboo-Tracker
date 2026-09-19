package org.audoiboo.tracker.plugin.flibusta

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.audoiboo.tracker.plugin.BookProvider
import org.audoiboo.tracker.plugin.CanonicalSeriesMatchInput
import org.audoiboo.tracker.plugin.SeriesCandidate
import org.audoiboo.tracker.plugin.SeriesDiscoveryProvider
import org.audoiboo.tracker.plugin.SeriesSearchProvider
import org.audoiboo.tracker.plugin.SeriesProvider
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
) : SourcePlugin, SeriesSearchProvider, SeriesDiscoveryProvider, SeriesProvider, BookProvider {

    override val descriptor = SourceDescriptor(
        id = ID,
        name = "Flibusta",
        version = 1,
        hosts = HOSTS,
        capabilities = setOf(
            SourceCapability.BOOK_LOOKUP,
            SourceCapability.SERIES_LOOKUP,
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
        return entries.values.toList().toCandidates(canonical.title)
    }

    override suspend fun resolveSeries(url: String): SourceSeries? {
        val parser = FlibustaParserRegistry.forUrl(url) ?: return null
        val response = runCatching { transport.get(url, requestHeaders()) }.getOrNull() ?: return null
        if (response.statusCode !in 200..299) return null
        val body = response.body.toString(Charsets.UTF_8)
        val entries = parser.parseCatalog(body, response.finalUrl)
        if (entries.isEmpty()) return null
        // On flibusta.site /s/<id> pages the book rows do not necessarily repeat a
        // link back to the current series, so parseCatalog() can legitimately return
        // entries with series == null. Derive the series title from the page heading.
        val document = org.jsoup.Jsoup.parse(body, response.finalUrl)
        val seriesTitle = entries.mapNotNull { it.series?.trim()?.takeIf(String::isNotBlank) }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            // Classic Flibusta exposes the useful series name in <title> even when
            // <h1> is only the site name. Prefer it before heuristic body parsing.
            ?: classicSiteTitleTag(document, response.finalUrl)
            ?: classicSiteSeriesTitle(document, response.finalUrl)
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() && !it.equals("Флибуста", ignoreCase = true) }
            ?: document.selectFirst("title")?.text()
                ?.replace(Regex("\\s*[|—-]\\s*(?:Flibusta|Флибуста|Lib\\.ru).*$", RegexOption.IGNORE_CASE), "")
                ?.trim()?.takeIf(String::isNotBlank)
            ?: return null
        val matching = entries.filter { it.series?.trim().equals(seriesTitle, ignoreCase = true) }
            .ifEmpty { entries }
        return SourceSeries(
            sourceId = ID,
            remoteId = Regex("/(?:s|series|books-series)/(\\d+)").find(response.finalUrl)?.groupValues?.getOrNull(1),
            url = response.finalUrl,
            title = seriesTitle,
            authors = matching.mapNotNull { it.author?.trim()?.takeIf(String::isNotBlank) }
                .distinct().map(::SourceAuthor),
            books = matching.distinctBy { normalizeUrl(it.url) }.map {
                SourceBookRef(it.remoteId, it.url, it.title, it.seriesNumber?.toDouble())
            },
        )
    }

    private fun classicSiteTitleTag(document: org.jsoup.nodes.Document, url: String): String? {
        if (FlibustaParserRegistry.forUrl(url)?.variant != FlibustaVariant.SITE ||
            !Regex("^/s/\\d+/?$").matches(pathOf(url))) return null
        return document.selectFirst("title")?.text()
            ?.replace(Regex("\\s*[|—-]\\s*(?:Flibusta|Флибуста|Lib\\.ru).*$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.takeIf { it.length >= 2 && !it.equals("Флибуста", ignoreCase = true) }
    }

    private fun classicSiteSeriesTitle(document: org.jsoup.nodes.Document, url: String): String? {
        if (FlibustaParserRegistry.forUrl(url)?.variant != FlibustaVariant.SITE ||
            !Regex("^/s/\\d+/?$").matches(pathOf(url))) return null
        val body = document.selectFirst("#main, #content, #bodyContent, main, .content") ?: document.body() ?: return null
        val label = body.select("*").firstOrNull { element ->
            element.ownText().trim().equals("Тип серии:", ignoreCase = true)
        }
        val root = label?.parent() ?: body
        val text = root.text().replace(Regex("\\s+"), " ").trim()
        return Regex("(.+?)\\s+Тип серии:", RegexOption.IGNORE_CASE).find(text)
            ?.groupValues?.getOrNull(1)
            ?.substringAfterLast("Главная »")
            ?.substringAfterLast("Книги")
            ?.trim()
            ?.takeIf { it.length >= 2 && !it.equals("Флибуста", ignoreCase = true) }
    }

    override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> {
        if (series.sourceId != ID) return emptyList()
        return series.books.mapNotNull { ref ->
            val listedTitle = ref.title?.trim()?.takeIf(String::isNotBlank)
            val loaded = loadBook(ref.url)
            when {
                // The classic /b/<id> page can expose only the site heading ("Флибуста")
                // as h1/title. The /s/<id> catalog row is the authoritative book title.
                // Never let that generic page heading collapse every series row into one
                // logical book during canonical dedupe.
                listedTitle != null && loaded != null -> loaded.copy(
                    remoteId = ref.remoteId ?: loaded.remoteId,
                    url = ref.url,
                    title = listedTitle,
                    authors = loaded.authors.ifEmpty { series.authors },
                    seriesTitle = series.title,
                    seriesNumber = ref.number ?: loaded.seriesNumber,
                )
                loaded != null && !loaded.title.equals("Флибуста", ignoreCase = true) -> loaded
                listedTitle != null -> SourceBook(
                    sourceId = ID,
                    remoteId = ref.remoteId,
                    url = ref.url,
                    title = listedTitle,
                    authors = series.authors,
                    seriesTitle = series.title,
                    seriesNumber = ref.number,
                )
                else -> null
            }
        }
    }

    override suspend fun loadBook(url: String): SourceBook? {
        val parser = FlibustaParserRegistry.forUrl(url) ?: return null
        val response = runCatching { transport.get(url, requestHeaders()) }.getOrNull() ?: return null
        if (response.statusCode !in 200..299) return null
        val page = parser.parseBookPage(response.body.toString(Charsets.UTF_8), response.finalUrl)
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
