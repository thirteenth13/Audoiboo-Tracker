package org.audoiboo.tracker

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder

internal data class FastBook(
    val title: String,
    val url: String,
    val author: String?,
    val coverUrl: String?,
    val seriesTitle: String? = null
)
internal data class FastResolvedSeries(val name: String, val url: String)

/** Fast first-stage parser. Returning null/empty deliberately falls back to WebView. */
internal object AudiobooFastParser {
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"
    private const val MAX_SERIES_PAGES = 100
    private const val MAX_AUTHOR_PAGES = 30

    private data class ParsedCard(
        val book: FastBook,
        val authorUrl: String?
    )

    fun resolveSeries(url: String): FastResolvedSeries? = runCatching {
        val doc = fetch(url)
        val location = doc.location().ifBlank { url }
        val uri = URI(location)
        if (uri.path.orEmpty().contains("/xfsearch/cikl/", true)) {
            val clean = location.replace(Regex("/page/\\d+/?$", RegexOption.IGNORE_CASE), "/")
            val segment = uri.path.split('/').filter { it.isNotBlank() }
                .let { p -> p.indexOfFirst { it.equals("cikl", true) }.let { i -> if (i >= 0) p.getOrNull(i + 1) else null } }
            val name = segment?.let { URLDecoder.decode(it, "UTF-8").replace('+', ' ') }?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("h1")?.text()?.trim()
            if (!name.isNullOrBlank()) FastResolvedSeries(name, clean) else null
        } else {
            val a = doc.select("a[href*=/xfsearch/cikl/]").firstOrNull { it.text().isNotBlank() }
            a?.absUrl("href")?.takeIf { it.isNotBlank() }?.let { FastResolvedSeries(a.text().trim(), it.replace(Regex("/page/\\d+/?$", RegexOption.IGNORE_CASE), "/")) }
        }
    }.getOrNull()

    fun parseSeries(url: String): List<FastBook>? = runCatching {
        val collected = linkedMapOf<String, FastBook>()
        val authorUrls = linkedSetOf<String>()
        val targetSeries = seriesNameFromUrl(url)

        crawlPages(url, MAX_SERIES_PAGES) { doc ->
            parseCards(doc).forEach { parsed ->
                collected[parsed.book.url] = parsed.book
                parsed.authorUrl?.let(authorUrls::add)
            }
        }

        // Audioboo occasionally exposes a just-published volume on the author's page before the
        // xfsearch/cikl listing catches up. Recover those cards from the same author and keep only
        // cards that explicitly declare the requested series. Membership policy later rejects
        // flattened subseries such as "Звездная Кровь. Белый Дьявол" when the site labels them only
        // as the parent series.
        if (!targetSeries.isNullOrBlank()) {
            authorUrls.forEach { authorUrl ->
                crawlPages(authorUrl, MAX_AUTHOR_PAGES) { doc ->
                    parseCards(doc).forEach { parsed ->
                        val cardSeries = parsed.book.seriesTitle ?: return@forEach
                        if (sameSeries(targetSeries, cardSeries)) {
                            collected.putIfAbsent(parsed.book.url, parsed.book)
                        }
                    }
                }
            }
        }

        collected.values.toList().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun findArchive(url: String): String? = runCatching {
        val doc = fetch(url)
        doc.select(".black_button_olako a[href*=/engine/go.php?url=], a[href*=/engine/go.php?url=]")
            .firstOrNull { a ->
                val text = a.text().lowercase()
                !text.contains("торрент") && !a.attr("href").startsWith("magnet:")
            }?.let { it.absUrl("href").ifBlank { it.attr("href") } }?.takeIf { it.startsWith("http") }
    }.getOrNull()

    private fun crawlPages(startUrl: String, maxPages: Int, consume: (Document) -> Unit) {
        val visited = mutableSetOf<String>()
        var next: String? = startUrl
        var pages = 0
        while (!next.isNullOrBlank() && visited.add(next) && pages++ < maxPages) {
            val doc = fetch(next)
            consume(doc)
            next = nextPage(doc)
        }
    }

    private fun parseCards(doc: Document): List<ParsedCard> = doc.select("article.card").mapNotNull { card ->
        val a = card.selectFirst("h2.card__title a[href]") ?: return@mapNotNull null
        val href = a.absUrl("href").ifBlank { a.attr("href") }
        val title = a.text().trim()
        if (href.isBlank() || title.isBlank()) return@mapNotNull null

        val authorLi = card.select("li").firstOrNull { it.text().trim().startsWith("Автор:", true) }
        val authorAnchor = authorLi?.selectFirst("a[href]")
        val author = authorAnchor?.text()?.trim().takeUnless { it.isNullOrBlank() }
            ?: authorLi?.text()?.replace(Regex("^Автор:\\s*", RegexOption.IGNORE_CASE), "")?.trim()
        val authorUrl = authorAnchor?.absUrl("href")?.takeIf { it.isNotBlank() }

        val seriesLi = card.select("li").firstOrNull {
            it.text().trim().startsWith("Серия:", true) || it.text().trim().startsWith("Серия ", true)
        }
        val seriesTitle = seriesLi?.selectFirst("a[href*=/xfsearch/cikl/]")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: seriesLi?.text()
                ?.replace(Regex("^Серия:?\\s*", RegexOption.IGNORE_CASE), "")
                ?.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val img = card.selectFirst("img")
        val cover = img?.let { it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src") }?.takeIf { it.isNotBlank() }

        ParsedCard(
            book = FastBook(title, href, author, cover, seriesTitle),
            authorUrl = authorUrl
        )
    }

    private fun nextPage(doc: Document): String? {
        val current = Regex("/page/(\\d+)/?", RegexOption.IGNORE_CASE).find(doc.location())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        val basePath = URI(doc.location()).path.replace(Regex("/page/\\d+/?$", RegexOption.IGNORE_CASE), "/")
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val u = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
            val m = Regex("/page/(\\d+)/?", RegexOption.IGNORE_CASE).find(u.path) ?: return@mapNotNull null
            val page = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val sameBase = u.path.replace(Regex("/page/\\d+/?$", RegexOption.IGNORE_CASE), "/") == basePath
            if (sameBase && page > current) page to href else null
        }.minByOrNull { it.first }?.second
    }

    private fun seriesNameFromUrl(url: String): String? = runCatching {
        val path = URI(url).path.orEmpty().split('/').filter { it.isNotBlank() }
        val index = path.indexOfFirst { it.equals("cikl", true) }
        path.getOrNull(index + 1)
            ?.let { URLDecoder.decode(it, "UTF-8").replace('+', ' ') }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun sameSeries(left: String, right: String): Boolean = normalizeSeries(left) == normalizeSeries(right)

    private fun normalizeSeries(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun fetch(url: String) = Jsoup.connect(url)
        .userAgent(UA)
        .referrer("https://audioboo.org/")
        .timeout(12_000)
        .followRedirects(true)
        .get()
}
