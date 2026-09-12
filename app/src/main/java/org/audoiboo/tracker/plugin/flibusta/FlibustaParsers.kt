package org.audoiboo.tracker.plugin.flibusta

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * HTML-only parsers for the three Flibusta sources used by the experimental ebook pipeline.
 *
 * They deliberately do not perform network requests, sleep for countdowns or execute JavaScript.
 * Transport/session handling belongs to the resolver layer. Keeping HTML parsing separate makes it
 * possible to test site changes with captured fixtures and avoids loading advertising scripts in a
 * WebView.
 */
enum class FlibustaVariant(val host: String) {
    SITE("flibusta.site"),
    ONE("flibusta.one"),
    NAME("flibusta.name")
}

enum class FlibustaFormat {
    FB2,
    EPUB,
    MOBI,
    PDF,
    TXT,
    UNKNOWN
}

data class FlibustaCatalogEntry(
    val remoteId: String?,
    val title: String,
    val url: String,
    val author: String? = null,
    val series: String? = null,
    val seriesNumber: Int? = null
)

data class FlibustaDownloadLink(
    val format: FlibustaFormat,
    val url: String,
    /** UI/server countdown observed on the page. Resolver may still discover that no wait is needed. */
    val waitSeconds: Int = 0,
    /** False for obvious ads/store links. Such links must never be treated as ebook downloads. */
    val trustedByParser: Boolean = true
)

data class FlibustaBookPage(
    val variant: FlibustaVariant,
    val remoteId: String?,
    val canonicalUrl: String,
    val title: String?,
    val author: String?,
    val authorUrl: String?,
    val series: String?,
    val seriesUrl: String?,
    val seriesNumber: Int?,
    val coverUrl: String?,
    val readerUrl: String?,
    val downloads: List<FlibustaDownloadLink>,
    val waitSeconds: Int
)

interface FlibustaHtmlParser {
    val variant: FlibustaVariant

    fun parseBookPage(html: String, pageUrl: String): FlibustaBookPage

    /**
     * Extracts lightweight book references from search/author/series/catalog pages.
     * The method intentionally does not fetch every book detail page.
     */
    fun parseCatalog(html: String, pageUrl: String): List<FlibustaCatalogEntry>
}

object FlibustaParserRegistry {
    val site: FlibustaHtmlParser = FlibustaSiteParser
    val one: FlibustaHtmlParser = FlibustaOneParser
    val name: FlibustaHtmlParser = FlibustaNameParser

    fun forUrl(url: String): FlibustaHtmlParser? = when (hostOf(url)) {
        FlibustaVariant.SITE.host, "www.${FlibustaVariant.SITE.host}" -> site
        FlibustaVariant.ONE.host, "www.${FlibustaVariant.ONE.host}" -> one
        FlibustaVariant.NAME.host, "www.${FlibustaVariant.NAME.host}" -> name
        else -> null
    }
}

object FlibustaSiteParser : BaseFlibustaParser(FlibustaVariant.SITE) {
    private val bookPath = Regex("^/b/(\\d+)(?:/.*)?$")

    override fun bookId(path: String): String? = bookPath.matchEntire(path)?.groupValues?.getOrNull(1)

    override fun isBookPath(path: String): Boolean = Regex("^/b/\\d+/?$").matches(path)

    override fun authorSelector(): String = "a[href~=(?i)^/a/\\d+/?$], a[href*='flibusta.site/a/']"

    override fun seriesSelector(): String = "a[href~=(?i)^/s/\\d+/?$], a[href*='flibusta.site/s/']"

    override fun readerSelector(): String = "a[href$='/read'], a[href*='/read?']"

    override fun directDownloadFromAnchor(anchor: Element, pageUrl: String, waitSeconds: Int): FlibustaDownloadLink? {
        val href = anchor.attr("href").trim()
        if (href.isBlank()) return null
        val absolute = absoluteUrl(pageUrl, href) ?: return null
        val path = pathOf(absolute)
        val format = when {
            Regex("^/b/\\d+/fb2(?:$|[/?#])", RegexOption.IGNORE_CASE).containsMatchIn(path) -> FlibustaFormat.FB2
            Regex("^/b/\\d+/epub(?:$|[/?#])", RegexOption.IGNORE_CASE).containsMatchIn(path) -> FlibustaFormat.EPUB
            Regex("^/b/\\d+/mobi(?:$|[/?#])", RegexOption.IGNORE_CASE).containsMatchIn(path) -> FlibustaFormat.MOBI
            else -> formatFrom(anchor, absolute)
        }
        if (format == FlibustaFormat.UNKNOWN) return null
        return FlibustaDownloadLink(format, absolute, waitSeconds = 0, trustedByParser = sameHost(absolute, pageUrl))
    }
}

object FlibustaOneParser : BaseFlibustaParser(FlibustaVariant.ONE) {
    private val bookPath = Regex("^/books/(\\d+)(?:-[^/]+)?/?(?:reading/?)?$")

    override fun bookId(path: String): String? = bookPath.matchEntire(path)?.groupValues?.getOrNull(1)

    override fun isBookPath(path: String): Boolean = Regex("^/books/\\d+(?:-[^/]+)?/?$").matches(path)

    override fun authorSelector(): String = "a[href*='/authors-books/'], a[href*='/authors/']"

    override fun seriesSelector(): String = "a[href*='/books-series/'], a[href*='/series/']"

    override fun readerSelector(): String = "a[href*='/reading/']"
}

object FlibustaNameParser : BaseFlibustaParser(FlibustaVariant.NAME) {
    private val bookPath = Regex("^/books/(\\d+)(?:-[^/]+)?/?(?:viewer/?)?$")

    override fun bookId(path: String): String? = bookPath.matchEntire(path)?.groupValues?.getOrNull(1)

    override fun isBookPath(path: String): Boolean = Regex("^/books/\\d+(?:-[^/]+)?/?$").matches(path)

    override fun authorSelector(): String = "a[href*='/authors/'], a[href*='/authors-books/']"

    override fun seriesSelector(): String = "a[href*='/books-series/'], a[href*='/series/']"

    override fun readerSelector(): String = "a[href*='/viewer/'], a[href*='/reading/']"
}

abstract class BaseFlibustaParser(
    final override val variant: FlibustaVariant
) : FlibustaHtmlParser {

    protected abstract fun bookId(path: String): String?
    protected abstract fun isBookPath(path: String): Boolean
    protected abstract fun authorSelector(): String
    protected abstract fun seriesSelector(): String
    protected abstract fun readerSelector(): String

    override fun parseBookPage(html: String, pageUrl: String): FlibustaBookPage {
        val document = Jsoup.parse(html, pageUrl)
        val canonical = canonicalUrl(document, pageUrl)
        val waitSeconds = extractWaitSeconds(document)
        val author = firstMeaningfulLink(document, authorSelector())
        val series = firstMeaningfulLink(document, seriesSelector())
        val seriesNumber = extractSeriesNumber(document, series?.first)
        val reader = firstMeaningfulLink(document, readerSelector())
        val downloads = document.select("a[href]")
            .mapNotNull { directDownloadFromAnchor(it, canonical, waitSeconds) }
            .distinctBy { it.format to normalizeUrl(it.url) }

        return FlibustaBookPage(
            variant = variant,
            remoteId = bookId(pathOf(canonical)),
            canonicalUrl = canonical,
            title = extractTitle(document),
            author = author?.first,
            authorUrl = author?.second,
            series = series?.first,
            seriesUrl = series?.second,
            seriesNumber = seriesNumber,
            coverUrl = extractCover(document, canonical),
            readerUrl = reader?.second,
            downloads = downloads,
            waitSeconds = waitSeconds
        )
    }

    override fun parseCatalog(html: String, pageUrl: String): List<FlibustaCatalogEntry> {
        val document = Jsoup.parse(html, pageUrl)
        val seen = linkedSetOf<String>()
        val output = mutableListOf<FlibustaCatalogEntry>()

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val absolute = absoluteUrl(pageUrl, href) ?: return@forEach
            val path = pathOf(absolute)
            if (!sameHostOrVariant(absolute) || !isBookPath(path)) return@forEach

            val title = cleanText(anchor.text()).takeIf { it.length >= 2 } ?: return@forEach
            val normalized = normalizeUrl(absolute)
            if (!seen.add(normalized)) return@forEach

            val parentText = cleanText(anchor.parent()?.text().orEmpty())
            output += FlibustaCatalogEntry(
                remoteId = bookId(path),
                title = title,
                url = absolute,
                author = nearbyAuthor(anchor),
                series = nearbySeries(anchor),
                seriesNumber = extractNumberNearBook(anchor, parentText)
            )
        }

        return output
    }

    protected open fun directDownloadFromAnchor(
        anchor: Element,
        pageUrl: String,
        waitSeconds: Int
    ): FlibustaDownloadLink? {
        val href = anchor.attr("href").trim()
        if (href.isBlank() || href.startsWith("javascript:", ignoreCase = true) || href == "#") return null

        val absolute = absoluteUrl(pageUrl, href) ?: return null
        val format = formatFrom(anchor, absolute)
        if (format == FlibustaFormat.UNKNOWN) return null

        // Stores/ads often use ebook format words in surrounding text. Keep them out of the resolver.
        val trusted = sameHostOrVariant(absolute) && !isKnownCommercialOrAdHost(hostOf(absolute))
        if (!trusted) return null

        return FlibustaDownloadLink(
            format = format,
            url = absolute,
            waitSeconds = waitSeconds,
            trustedByParser = true
        )
    }

    private fun extractTitle(document: Document): String? {
        val candidates = sequenceOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            document.selectFirst(".book-title")?.text(),
            document.title()
        )
        return candidates.mapNotNull { it?.let(::cleanText)?.takeIf(String::isNotBlank) }
            .map { stripSiteSuffix(it) }
            .firstOrNull()
    }

    private fun extractCover(document: Document, pageUrl: String): String? {
        val raw = sequenceOf(
            document.selectFirst("meta[property=og:image]")?.attr("content"),
            document.selectFirst("img[itemprop=image]")?.attr("src"),
            document.selectFirst(".book img[src]")?.attr("src"),
            document.selectFirst("img[src*='cover']")?.attr("src")
        ).firstOrNull { !it.isNullOrBlank() } ?: return null
        return absoluteUrl(pageUrl, raw)
    }

    private fun firstMeaningfulLink(document: Document, selector: String): Pair<String, String>? =
        document.select(selector).asSequence().mapNotNull { link ->
            val text = cleanText(link.text()).takeIf { it.length >= 2 } ?: return@mapNotNull null
            val href = link.attr("href").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val url = absoluteUrl(document.location(), href) ?: return@mapNotNull null
            text to url
        }.firstOrNull()

    private fun extractSeriesNumber(document: Document, seriesTitle: String?): Int? {
        val title = seriesTitle ?: return null
        val text = cleanText(document.body()?.text().orEmpty())
        val escaped = Regex.escape(title)
        val patterns = listOf(
            Regex("$escaped\\s*[#№]\\s*(\\d{1,4})", RegexOption.IGNORE_CASE),
            Regex("$escaped.{0,30}?(?:книга|том)\\s*(\\d{1,4})", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    private fun nearbyAuthor(anchor: Element): String? =
        anchor.closest("article, li, tr, .book, .book-item, .card, .row")
            ?.selectFirst(authorSelector())
            ?.text()
            ?.let(::cleanText)
            ?.takeIf(String::isNotBlank)

    private fun nearbySeries(anchor: Element): String? =
        anchor.closest("article, li, tr, .book, .book-item, .card, .row")
            ?.selectFirst(seriesSelector())
            ?.text()
            ?.let(::cleanText)
            ?.takeIf(String::isNotBlank)

    private fun extractNumberNearBook(anchor: Element, parentText: String): Int? {
        val ownPrefix = anchor.previousSibling()?.toString().orEmpty()
        val local = cleanText("$ownPrefix $parentText")
        return Regex("(?:^|\\s)[#№]\\s*(\\d{1,4})(?:\\s|$)").find(local)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun sameHostOrVariant(url: String): Boolean {
        val host = hostOf(url)
        return host == variant.host || host == "www.${variant.host}"
    }
}

internal fun extractWaitSeconds(document: Document): Int {
    val text = cleanText(document.body()?.text().orEmpty())
    val patterns = listOf(
        Regex("(?:время\\s+ожидания\\s*:?\\s*)(\\d{1,3})\\s*сек", RegexOption.IGNORE_CASE),
        Regex("(?:через\\s+)(\\d{1,3})\\s*сек", RegexOption.IGNORE_CASE),
        Regex("(?:wait|countdown)[^0-9]{0,20}(\\d{1,3})\\s*(?:s|sec|seconds?)", RegexOption.IGNORE_CASE)
    )
    return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        ?.coerceIn(0, 120)
        ?: 0
}

internal fun formatFrom(anchor: Element, absoluteUrl: String): FlibustaFormat {
    val text = cleanText(anchor.text()).lowercase()
    val href = absoluteUrl.lowercase()
    fun has(token: String) =
        Regex("(?:^|[^a-z0-9])${Regex.escape(token)}(?:[^a-z0-9]|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn("$text $href")

    return when {
        has("fb2") -> FlibustaFormat.FB2
        has("epub") -> FlibustaFormat.EPUB
        has("mobi") -> FlibustaFormat.MOBI
        has("pdf") -> FlibustaFormat.PDF
        has("txt") -> FlibustaFormat.TXT
        else -> FlibustaFormat.UNKNOWN
    }
}

internal fun canonicalUrl(document: Document, fallback: String): String {
    val raw = document.selectFirst("link[rel=canonical]")?.attr("href")
        ?: document.selectFirst("meta[property=og:url]")?.attr("content")
    return raw?.takeIf(String::isNotBlank)?.let { absoluteUrl(fallback, it) } ?: fallback
}

internal fun absoluteUrl(base: String, href: String): String? = runCatching {
    URI(base).resolve(href.trim()).toString()
}.getOrNull()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

internal fun pathOf(url: String): String = runCatching { URI(url).path ?: "/" }.getOrDefault("/")

internal fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")

internal fun sameHost(a: String, b: String): Boolean = hostOf(a).removePrefix("www.") == hostOf(b).removePrefix("www.")

internal fun normalizeUrl(url: String): String = runCatching {
    val uri = URI(url)
    URI(uri.scheme?.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path.trimEnd('/'), uri.query, null).toString()
}.getOrDefault(url).trimEnd('/')

internal fun cleanText(value: String): String = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

private fun stripSiteSuffix(value: String): String = value
    .replace(Regex("\\s*[|—-]\\s*Флибуста.*$", RegexOption.IGNORE_CASE), "")
    .trim()

private fun isKnownCommercialOrAdHost(host: String): Boolean {
    val normalized = host.removePrefix("www.")
    return normalized == "litres.ru" || normalized.endsWith(".litres.ru") ||
        normalized == "litres.com" || normalized.endsWith(".litres.com")
}
