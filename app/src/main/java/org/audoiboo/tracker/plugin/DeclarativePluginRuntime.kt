package org.audoiboo.tracker.plugin

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed interface DeclarativeEntrypoint {
    data class SeriesLookup(
        val title: String,
        val description: String? = null,
        val remoteId: String? = null,
        val books: RepeatedFields? = null,
        val followLink: String? = null,
        val titleRegex: String? = null,
        val supplement: SeriesSupplement? = null
    ) : DeclarativeEntrypoint

    data class SeriesSearch(
        val searchUrl: String,
        val items: RepeatedFields,
        val maxResults: Int = 10
    ) : DeclarativeEntrypoint

    data class BookLookup(
        val title: String,
        val author: String? = null,
        val remoteId: String? = null,
        val seriesTitle: String? = null,
        val seriesNumber: String? = null,
        val coverUrl: String? = null,
        val description: String? = null,
        val titleRegex: String? = null
    ) : DeclarativeEntrypoint

    data class DownloadResolution(
        val items: RepeatedFields,
        val type: DownloadType = DownloadType.ARCHIVE,
        val fileName: String? = null
    ) : DeclarativeEntrypoint
}

data class RepeatedFields(
    val item: String,
    val title: String? = null,
    val link: String,
    val author: String? = null,
    val remoteId: String? = null,
    val number: String? = null
)

data class SeriesSupplement(
    val startLink: String,
    val items: RepeatedFields,
    val seriesTitle: String,
    val nextPage: String? = null,
    val maxPages: Int = 1
)

fun interface DeclarativeEntrypointDecoder {
    fun decode(json: String): DeclarativeEntrypoint
}

object JsonDeclarativeEntrypointDecoder : DeclarativeEntrypointDecoder {
    override fun decode(json: String): DeclarativeEntrypoint {
        val root = JSONObject(json)
        return when (root.getString("operation")) {
            "seriesLookup" -> {
                val series = root.getJSONObject("series")
                val supplement = series.optJSONObject("supplement")?.let { item ->
                    SeriesSupplement(
                        startLink = item.getString("startLink"),
                        items = item.getJSONObject("items").toRepeatedFields(),
                        seriesTitle = item.getString("seriesTitle"),
                        nextPage = item.optString("nextPage").takeIf { it.isNotBlank() },
                        maxPages = item.optInt("maxPages", 1).coerceIn(1, 10)
                    )
                }
                DeclarativeEntrypoint.SeriesLookup(
                    title = series.getString("title"),
                    description = series.optString("description").takeIf { it.isNotBlank() },
                    remoteId = series.optString("remoteId").takeIf { it.isNotBlank() },
                    books = series.optJSONObject("books")?.toRepeatedFields(),
                    followLink = series.optString("followLink").takeIf { it.isNotBlank() },
                    titleRegex = series.optString("titleRegex").takeIf { it.isNotBlank() },
                    supplement = supplement
                )
            }
            "seriesSearch" -> {
                DeclarativeEntrypoint.SeriesSearch(
                    searchUrl = root.getString("searchUrl"),
                    items = root.getJSONObject("items").toRepeatedFields(),
                    maxResults = root.optInt("maxResults", 10).coerceIn(1, 50)
                )
            }
            "bookLookup" -> {
                val book = root.getJSONObject("book")
                DeclarativeEntrypoint.BookLookup(
                    title = book.getString("title"),
                    author = book.optString("author").takeIf { it.isNotBlank() },
                    remoteId = book.optString("remoteId").takeIf { it.isNotBlank() },
                    seriesTitle = book.optString("seriesTitle").takeIf { it.isNotBlank() },
                    seriesNumber = book.optString("seriesNumber").takeIf { it.isNotBlank() },
                    coverUrl = book.optString("coverUrl").takeIf { it.isNotBlank() },
                    description = book.optString("description").takeIf { it.isNotBlank() },
                    titleRegex = book.optString("titleRegex").takeIf { it.isNotBlank() }
                )
            }
            "downloadResolution" -> {
                val items = root.getJSONObject("items")
                DeclarativeEntrypoint.DownloadResolution(
                    items = items.toRepeatedFields(),
                    type = root.optString("type", DownloadType.ARCHIVE.name).let(DownloadType::valueOf),
                    fileName = root.optString("fileName").takeIf { it.isNotBlank() }
                )
            }
            else -> error("Unsupported declarative operation")
        }
    }

    private fun JSONObject.toRepeatedFields() = RepeatedFields(
        item = getString("item"),
        title = optString("title").takeIf { it.isNotBlank() },
        link = getString("link"),
        author = optString("author").takeIf { it.isNotBlank() },
        remoteId = optString("remoteId").takeIf { it.isNotBlank() },
        number = optString("number").takeIf { it.isNotBlank() }
    )
}

/**
 * Executes declarative selectors inside the host sandbox. The package supplies only data/rules:
 * no reflection, Dex/Jar loading, Android Context, files or direct networking are exposed.
 */
class DeclarativePluginRuntime(
    private val sandbox: PluginSandbox,
    private val decoder: DeclarativeEntrypointDecoder = JsonDeclarativeEntrypointDecoder
) {
    fun resolveSeries(manifest: PluginPackageManifest, packageDir: File, url: String): SourceSeries? {
        requireCapability(manifest, SourceCapability.SERIES_LOOKUP)
        val spec = loadEntrypoint(manifest, packageDir, "seriesLookup") as? DeclarativeEntrypoint.SeriesLookup
            ?: throw PluginSandboxViolation("seriesLookup entrypoint has wrong operation")
        val session = sandbox.open(manifest)
        var response = session.httpGet(url)
        if (response.statusCode !in 200..299) return null
        var document = Jsoup.parse(response.body, response.finalUrl)
        spec.followLink?.let { selector ->
            val follow = extract(document, selector)?.takeIf { it.isNotBlank() } ?: return@let
            val followUrl = resolveUrl(document, follow)
            val followed = session.httpGet(followUrl)
            if (followed.statusCode in 200..299) {
                response = followed
                document = Jsoup.parse(followed.body, followed.finalUrl)
            }
        }
        var title = extract(document, spec.title)?.takeIf { it.isNotBlank() } ?: return null
        title = applyRegex(title, spec.titleRegex)
        val books = buildList {
            spec.books?.let { fields -> addAll(extractBookRefs(document, fields)) }
            spec.supplement?.let { supplement ->
                addAll(loadSupplementRefs(session, document, title, supplement))
            }
        }.distinctBy { SourceKeys.normalizeUrl(it.url) }
        session.requireOutputSize(books.size)
        val authors = spec.books?.author?.let { selector ->
            document.select(spec.books.item)
                .mapNotNull { extract(it, selector)?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
                .map(::SourceAuthor)
        }.orEmpty()
        return SourceSeries(
            sourceId = manifest.id,
            remoteId = spec.remoteId?.let { extract(document, it) }?.takeIf { it.isNotBlank() },
            url = response.finalUrl,
            title = title,
            description = spec.description?.let { extract(document, it) }?.takeIf { it.isNotBlank() },
            authors = authors,
            books = books
        )
    }

    fun searchSeries(manifest: PluginPackageManifest, packageDir: File, query: SeriesSearchQuery): List<SeriesCandidate> {
        requireCapability(manifest, SourceCapability.SERIES_SEARCH)
        val spec = loadEntrypoint(manifest, packageDir, "seriesSearch") as? DeclarativeEntrypoint.SeriesSearch
            ?: throw PluginSandboxViolation("seriesSearch entrypoint has wrong operation")
        val encoded = URLEncoder.encode(query.title.trim(), StandardCharsets.UTF_8.name())
        val searchUrl = spec.searchUrl.replace("{query}", encoded)
        if (searchUrl == spec.searchUrl) throw PluginSandboxViolation("seriesSearch searchUrl must contain {query}")
        val session = sandbox.open(manifest)
        val response = session.httpGet(searchUrl)
        if (response.statusCode !in 200..299) return emptyList()
        val document = Jsoup.parse(response.body, response.finalUrl)
        val results = document.select(spec.items.item)
            .asSequence()
            .mapNotNull { item ->
                val link = extract(item, spec.items.link)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = spec.items.title?.let { extract(item, it) }?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val author = spec.items.author?.let { extract(item, it) }?.takeIf { it.isNotBlank() }
                SeriesCandidate(
                    series = SourceSeries(
                        sourceId = manifest.id,
                        remoteId = spec.items.remoteId?.let { extract(item, it) }?.takeIf { it.isNotBlank() },
                        url = resolveUrl(item, link),
                        title = title,
                        authors = author?.let { listOf(SourceAuthor(it)) }.orEmpty()
                    )
                )
            }
            .distinctBy { it.series.url }
            .take(spec.maxResults)
            .toList()
        session.requireOutputSize(results.size)
        return results
    }

    /**
     * Izib has a stable authors directory and author pages even though no stable generic search URL
     * was verified. This bounded fallback uses the first canonical author: it scans only a small
     * number of directory pages for that author's link, then reads that one author page and returns
     * matching /serie links. All requests still pass through the normal sandbox host/budget checks.
     */
    fun discoverIzibSeries(
        manifest: PluginPackageManifest,
        canonical: CanonicalSeriesMatchInput,
        maxAuthorPages: Int = 12
    ): List<SeriesCandidate> {
        requireCapability(manifest, SourceCapability.SERIES_DISCOVERY)
        if (manifest.id != "izib") return emptyList()
        val author = canonical.authors.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val expectedAuthor = SourceIdentityMatcher.normalizeTitle(author)
        val expectedSeries = SourceIdentityMatcher.normalizeTitle(canonical.title)
        if (expectedAuthor.isBlank() || expectedSeries.isBlank()) return emptyList()
        val session = sandbox.open(manifest)

        var authorUrl: String? = null
        for (page in 1..maxAuthorPages.coerceIn(1, 12)) {
            val url = if (page == 1) "https://pda.izib.uk/authors" else "https://pda.izib.uk/authors?p=$page"
            val response = session.httpGet(url)
            if (response.statusCode !in 200..299) continue
            val document = Jsoup.parse(response.body, response.finalUrl)
            authorUrl = document.select("a[href*='/author']")
                .firstOrNull { link -> SourceIdentityMatcher.normalizeTitle(link.text()) == expectedAuthor }
                ?.let { link -> resolveUrl(link, link.attr("href")) }
            if (authorUrl != null) break
        }
        val resolvedAuthorUrl = authorUrl ?: return emptyList()
        val authorResponse = session.httpGet(resolvedAuthorUrl)
        if (authorResponse.statusCode !in 200..299) return emptyList()
        val authorDocument = Jsoup.parse(authorResponse.body, authorResponse.finalUrl)
        val results = authorDocument.select("a[href*='/serie']")
            .asSequence()
            .mapNotNull { link ->
                val title = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val normalized = SourceIdentityMatcher.normalizeTitle(title)
                if (normalized != expectedSeries) return@mapNotNull null
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SeriesCandidate(
                    SourceSeries(
                        sourceId = manifest.id,
                        url = resolveUrl(link, href),
                        title = title,
                        authors = listOf(SourceAuthor(author, resolvedAuthorUrl))
                    )
                )
            }
            .distinctBy { SourceKeys.normalizeUrl(it.series.url) }
            .take(5)
            .toList()
        session.requireOutputSize(results.size)
        return results
    }

    fun resolveBook(manifest: PluginPackageManifest, packageDir: File, url: String): SourceBook? {
        requireCapability(manifest, SourceCapability.BOOK_LOOKUP)
        val spec = loadEntrypoint(manifest, packageDir, "bookLookup") as? DeclarativeEntrypoint.BookLookup
            ?: throw PluginSandboxViolation("bookLookup entrypoint has wrong operation")
        val session = sandbox.open(manifest)
        val response = session.httpGet(url)
        if (response.statusCode !in 200..299) return null
        val document = Jsoup.parse(response.body, response.finalUrl)
        var title = extract(document, spec.title)?.takeIf { it.isNotBlank() } ?: return null
        title = applyRegex(title, spec.titleRegex)
        return SourceBook(
            sourceId = manifest.id,
            remoteId = spec.remoteId?.let { extract(document, it) }?.takeIf { it.isNotBlank() },
            url = response.finalUrl,
            title = title,
            authors = spec.author?.let { extract(document, it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { listOf(SourceAuthor(it)) }
                .orEmpty(),
            seriesTitle = spec.seriesTitle?.let { extract(document, it) }?.takeIf { it.isNotBlank() },
            seriesNumber = spec.seriesNumber?.let { extract(document, it) }?.let(::parseNumber),
            coverUrl = spec.coverUrl?.let { extract(document, it) }?.takeIf { it.isNotBlank() }?.let { resolveUrl(document, it) },
            description = spec.description?.let { extract(document, it) }?.takeIf { it.isNotBlank() }
        )
    }

    fun resolveDownloads(manifest: PluginPackageManifest, packageDir: File, url: String): List<DownloadCandidate> {
        requireCapability(manifest, SourceCapability.DOWNLOAD_RESOLUTION)
        val spec = loadEntrypoint(manifest, packageDir, "downloadResolution") as? DeclarativeEntrypoint.DownloadResolution
            ?: throw PluginSandboxViolation("downloadResolution entrypoint has wrong operation")
        val session = sandbox.open(manifest)
        val response = session.httpGet(url)
        if (response.statusCode !in 200..299) return emptyList()
        val document = Jsoup.parse(response.body, response.finalUrl)
        val results = document.select(spec.items.item)
            .mapNotNull { item ->
                val raw = extract(item, spec.items.link)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DownloadCandidate(
                    type = spec.type,
                    url = resolveUrl(item, raw),
                    fileName = spec.fileName
                )
            }
            .distinctBy { it.url }
        session.requireOutputSize(results.size)
        return results
    }

    private fun loadSupplementRefs(
        session: PluginSandboxSession,
        seriesDocument: Element,
        expectedTitle: String,
        supplement: SeriesSupplement
    ): List<SourceBookRef> {
        val start = extract(seriesDocument, supplement.startLink)?.takeIf { it.isNotBlank() } ?: return emptyList()
        var nextUrl: String? = resolveUrl(seriesDocument, start)
        val expected = SourceIdentityMatcher.normalizeTitle(expectedTitle)
        val results = mutableListOf<SourceBookRef>()
        val visited = hashSetOf<String>()
        repeat(supplement.maxPages) {
            val current = nextUrl ?: return@repeat
            if (!visited.add(current)) return@repeat
            val response = session.httpGet(current)
            if (response.statusCode !in 200..299) return@repeat
            val document = Jsoup.parse(response.body, response.finalUrl)
            document.select(supplement.items.item).forEach { item ->
                val itemSeries = extract(item, supplement.seriesTitle)
                    ?.takeIf { it.isNotBlank() }
                    ?.let(SourceIdentityMatcher::normalizeTitle)
                if (itemSeries != expected) return@forEach
                val link = extract(item, supplement.items.link)?.takeIf { it.isNotBlank() } ?: return@forEach
                results += SourceBookRef(
                    remoteId = supplement.items.remoteId?.let { extract(item, it) }?.takeIf { it.isNotBlank() },
                    url = resolveUrl(item, link),
                    title = supplement.items.title?.let { extract(item, it) }?.takeIf { it.isNotBlank() },
                    number = supplement.items.number?.let { extract(item, it) }?.let(::parseNumber)
                )
            }
            nextUrl = supplement.nextPage
                ?.let { extract(document, it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(document, it) }
        }
        session.requireOutputSize(results.size)
        return results
    }

    private fun extractBookRefs(document: Element, fields: RepeatedFields): List<SourceBookRef> =
        document.select(fields.item).mapNotNull { item ->
            val link = extract(item, fields.link)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SourceBookRef(
                remoteId = fields.remoteId?.let { extract(item, it) }?.takeIf { it.isNotBlank() },
                url = resolveUrl(item, link),
                title = fields.title?.let { extract(item, it) }?.takeIf { it.isNotBlank() },
                number = fields.number?.let { extract(item, it) }?.let(::parseNumber)
            )
        }

    private fun loadEntrypoint(manifest: PluginPackageManifest, packageDir: File, name: String): DeclarativeEntrypoint {
        val relative = manifest.entrypoints[name] ?: throw PluginSandboxViolation("Missing $name entrypoint")
        if (!PluginPackagePolicy.isSafeRelativePath(relative)) throw PluginSandboxViolation("Unsafe entrypoint path")
        val root = packageDir.canonicalFile.toPath()
        val file = File(packageDir, relative).canonicalFile
        if (!file.toPath().startsWith(root)) throw PluginSandboxViolation("Entrypoint escapes package directory")
        if (!file.isFile) throw PluginSandboxViolation("Entrypoint file is missing")
        if (file.length() > MAX_PLUGIN_ENTRYPOINT_BYTES) throw PluginSandboxViolation("Entrypoint file exceeds size limit")
        return decoder.decode(file.readText())
    }

    private fun requireCapability(manifest: PluginPackageManifest, capability: SourceCapability) {
        if (capability !in manifest.capabilities) throw PluginSandboxViolation("Plugin did not declare $capability")
    }

    private fun extract(element: Element, expression: String): String? {
        val alternatives = expression.split("||").map { it.trim() }.filter { it.isNotBlank() }
        alternatives.forEach { alternative ->
            val (selector, attribute) = splitSelectorAttribute(alternative)
            val target = if (selector.isBlank()) element else element.selectFirst(selector) ?: return@forEach
            val value = when {
                attribute == null -> target.text()
                attribute.equals("text", true) -> target.text()
                else -> target.attr(attribute)
            }.trim()
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun splitSelectorAttribute(expression: String): Pair<String, String?> {
        val marker = expression.lastIndexOf('@')
        if (marker < 0) return expression to null
        return expression.substring(0, marker).trim() to expression.substring(marker + 1).trim()
    }

    private fun resolveUrl(element: Element, raw: String): String =
        element.baseUri().let { base -> runCatching { java.net.URI(base).resolve(raw).toString() }.getOrDefault(raw) }

    private fun applyRegex(value: String, pattern: String?): String {
        if (pattern.isNullOrBlank()) return value.trim()
        val match = Regex(pattern).find(value) ?: return value.trim()
        return match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.trim() ?: match.value.trim()
    }

    private fun parseNumber(value: String): Double? =
        Regex("-?[0-9]+(?:[.,][0-9]+)?").find(value)?.value?.replace(',', '.')?.toDoubleOrNull()
}
