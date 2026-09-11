package org.audoiboo.tracker

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

internal object AudiobooSearchParser {
    private const val UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36"

    fun fetch(url: String): List<FastBook> = runCatching {
        val response = Jsoup.connect(url)
            .userAgent(UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ru-RU,ru;q=0.9,uk-UA;q=0.8,uk;q=0.7,en;q=0.5")
            .timeout(12_000)
            .followRedirects(true)
            .execute()
        parse(response.body(), response.url().toString())
    }.getOrDefault(emptyList())

    fun parse(html: String, baseUrl: String): List<FastBook> {
        val doc = Jsoup.parse(html, baseUrl)
        val baseHost = runCatching { URI(baseUrl).host?.lowercase() }.getOrNull()
        val seen = linkedMapOf<String, FastBook>()

        val containers = doc.select(
            "article.card, article.shortstory, .shortstory, .short, .story, .post, .news-item, .item, .card"
        )

        containers.forEach { container ->
            parseContainer(container, baseHost)?.let { seen.putIfAbsent(it.url, it) }
        }

        if (seen.isEmpty()) {
            doc.select("h1 a[href], h2 a[href], h3 a[href], .title a[href], .short-title a[href]")
                .forEach { anchor ->
                    parseAnchor(anchor, anchor.closest("article, div, li") ?: anchor.parent(), baseHost)
                        ?.let { seen.putIfAbsent(it.url, it) }
                }
        }

        return seen.values.toList()
    }

    private fun parseContainer(container: Element, baseHost: String?): FastBook? {
        val anchor = container.selectFirst(
            "h1 a[href], h2 a[href], h3 a[href], .card__title a[href], .title a[href], .short-title a[href], a[href]"
        ) ?: return null
        return parseAnchor(anchor, container, baseHost)
    }

    private fun parseAnchor(anchor: Element, container: Element?, baseHost: String?): FastBook? {
        val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
        val title = anchor.text().trim()
        if (href.isBlank() || title.length < 2 || !looksLikeBookUrl(href, baseHost)) return null

        val context = container ?: anchor.parent()
        val authorAnchor = context?.selectFirst(
            "a[href*=/xfsearch/avtora/], a[href*=/xfsearch/author/], a[href*=/author/], a[href*=/authors/]"
        )
        val author = authorAnchor?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: context?.text()?.let { text ->
                Regex("(?i)(?:Автор|Автор книги)\\s*[:—-]\\s*([^|•\\n]+)")
                    .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }

        val seriesAnchor = context?.selectFirst("a[href*=/xfsearch/cikl/]")
        val series = seriesAnchor?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: context?.text()?.let { text ->
                Regex("(?i)Серия\\s*[:—-]\\s*([^|•\\n]+)")
                    .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }

        val image = context?.selectFirst("img")
        val cover = image?.let {
            when {
                it.hasAttr("data-src") -> it.absUrl("data-src").ifBlank { it.attr("data-src") }
                else -> it.absUrl("src").ifBlank { it.attr("src") }
            }
        }?.takeIf { it.startsWith("http") }

        return FastBook(title = title, url = href, author = author, coverUrl = cover, seriesTitle = series)
    }

    private fun looksLikeBookUrl(url: String, baseHost: String?): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (baseHost != null && host != baseHost && host != "www.$baseHost" && "www.$host" != baseHost) return false
        val path = uri.path.orEmpty()
        if (path.isBlank() || path == "/") return false
        val lower = url.lowercase()
        if (lower.contains("/xfsearch/") || lower.contains("/engine/") || lower.contains("/page/") || lower.contains("do=search")) return false
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")) return false
        return true
    }
}
