package org.audoiboo.tracker

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

internal data class FastBook(val title: String, val url: String, val author: String?, val coverUrl: String?)
internal data class FastResolvedSeries(val name: String, val url: String)

/** Fast first-stage parser. Returning null/empty deliberately falls back to WebView. */
internal object AudiobooFastParser {
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"

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
        val visited = mutableSetOf<String>()
        var next: String? = url
        var pages = 0
        while (!next.isNullOrBlank() && visited.add(next) && pages++ < 100) {
            val doc = fetch(next)
            for (card in doc.select("article.card")) {
                val a = card.selectFirst("h2.card__title a[href]") ?: continue
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                val title = a.text().trim()
                if (href.isBlank() || title.isBlank()) continue
                val author = card.select("li").firstOrNull { it.text().trim().startsWith("Автор:", true) }
                    ?.let { li -> li.selectFirst("a")?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: li.text().replace(Regex("^Автор:\\s*", RegexOption.IGNORE_CASE), "").trim() }
                val img = card.selectFirst("img")
                val cover = img?.let { it.absUrl(if (it.hasAttr("data-src")) "data-src" else "src") }?.takeIf { it.isNotBlank() }
                collected[href] = FastBook(title, href, author, cover)
            }
            val current = Regex("/page/(\\d+)/?", RegexOption.IGNORE_CASE).find(doc.location())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
            val basePath = URI(doc.location()).path.replace(Regex("/page/\\d+/?$", RegexOption.IGNORE_CASE), "/")
            next = doc.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val u = runCatching { URI(href) }.getOrNull() ?: return@mapNotNull null
                val m = Regex("/page/(\\d+)/?", RegexOption.IGNORE_CASE).find(u.path) ?: return@mapNotNull null
                val page = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val sameBase = u.path.replace(Regex("/page/\\d+/?$", RegexOption.IGNORE_CASE), "/") == basePath
                if (sameBase && page > current) page to href else null
            }.minByOrNull { it.first }?.second
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

    private fun fetch(url: String) = Jsoup.connect(url)
        .userAgent(UA)
        .referrer("https://audioboo.org/")
        .timeout(12_000)
        .followRedirects(true)
        .get()
}
