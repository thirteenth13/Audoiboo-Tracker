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
        val titleRegex: String? = null
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
        val description: String? = null
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

fun interface DeclarativeEntrypointDecoder {
    fun decode(json: String): DeclarativeEntrypoint
}

object JsonDeclarativeEntrypointDecoder : DeclarativeEntrypointDecoder {
    override fun decode(json: String): DeclarativeEntrypoint {
        val root = JSONObject(json)
        return when (root.getString("operation")) {
            "seriesLookup" -> {
                val series = root.getJSONObject("series")
                DeclarativeEntrypoint.SeriesLookup(
                    title = series.getString("title"),
                    description = series.optString("description").takeIf { it.isNotBlank() },
                    remoteId = series.optString("remoteId").takeIf { it.isNotBlank() },
                    books = series.optJSONObject("books")?.toRepeatedFields(),
                    followLink = series.optString("followLink").takeIf { it.isNotBlank() },
                    titleRegex = series.optString("titleRegex").takeIf { it.isNotBlank() }
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
                    description = book.optString("description").takeIf { it.isNotBlank() }
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
        spec.titleRegex?.let { regex ->
            val match = runCatching { Regex(regex).find(title) }.getOrNull()
            if (match != null) title = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
        }
        val books = spec.books?.let { fields ->
            document.select(fields.item).mapNotNull { item ->
                val link = extract(item, fields.link)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SourceBookRef(
                    remoteId = fields.remoteId?.let { extract(item, it) }?.takeIf { it.isNotBlank() },
                    url = resolveUrl(item, link),
                    title = fields.title?.let { extract(item, it) }?.takeIf { it.isNotBlank() },
                    number = fields.number?.let { extract(item, it) }?.toDoubleOrNull()
                )
            }
        }.orEmpty()
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

    fun resolveBook(manifest: PluginPackageManifest, packageDir: File, url: String): SourceBook? {
        requireCapability(manifest, SourceCapability.BOOK_LOOKUP)
        val spec = loadEntrypoint(manifest, packageDir, "bookLookup") as? DeclarativeEntrypoint.BookLookup
            ?: throw PluginSandboxViolation("bookLookup entrypoint has wrong operation")
        val session = sandbox.open(manifest)
        val response = session.httpGet(url)
        if (response.statusCode !in 200..299) return null
        val document = Jsoup.parse(response.body, response.finalUrl)
        val title = extract(document, spec.title)?.takeIf { it.isNotBlank() } ?: return null
        val author = spec.author?.let { extract(document, it) }?.takeIf { it.isNotBlank() }
        val cover = spec.coverUrl?.let { extract(document, it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(document, it) }
        return SourceBook(
            sourceId = manifest.id,
            remoteId = spec.remoteId?.let { extract(document, it) }?.takeIf { it.isNotBlank() },
            url = response.finalUrl,
            title = title,
            authors = author?.let { listOf(SourceAuthor(it)) }.orEmpty(),
            seriesTitle = spec.seriesTitle?.let { extract(document, it) }?.takeIf { it.isNotBlank() },
            seriesNumber = spec.seriesNumber?.let { extract(document, it) }?.toDoubleOrNull(),
            coverUrl = cover,
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
        val results = document.select(spec.items.item).mapNotNull { item ->
            val link = extract(item, spec.items.link)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DownloadCandidate(
                type = spec.type,
                url = resolveUrl(item, link),
                fileName = spec.fileName?.let { extract(item, it) }?.takeIf { it.isNotBlank() }
            )
        }.distinctBy { it.url }
        session.requireOutputSize(results.size)
        return results
    }

    private fun loadEntrypoint(manifest: PluginPackageManifest, packageDir: File, name: String): DeclarativeEntrypoint {
        if (manifest.runtime != PluginRuntime.DECLARATIVE) throw PluginSandboxViolation("Plugin is not declarative")
        val relative = manifest.entrypoints[name] ?: throw PluginSandboxViolation("Missing $name entrypoint")
        if (!PluginPackagePolicy.isSafeRelativePath(relative)) throw PluginSandboxViolation("Unsafe entrypoint path")
        val root = packageDir.canonicalFile
        val file = File(root, relative).canonicalFile
        if (!file.toPath().startsWith(root.toPath()) || !file.isFile) throw PluginSandboxViolation("Entrypoint file is missing")
        if (file.length() > 256L * 1024L) throw PluginSandboxViolation("Entrypoint file is too large")
        return runCatching { decoder.decode(file.readText()) }
            .getOrElse { throw PluginSandboxViolation("Invalid declarative entrypoint: ${it.message ?: "parse error"}") }
    }

    private fun requireCapability(manifest: PluginPackageManifest, capability: SourceCapability) {
        if (capability !in manifest.capabilities) throw PluginSandboxViolation("Plugin does not declare $capability")
    }

    private fun extract(root: Element, expression: String): String? {
        val split = expression.lastIndexOf('@')
        val selector = if (split >= 0) expression.substring(0, split) else expression
        val attribute = if (split >= 0) expression.substring(split + 1) else null
        val element = if (selector.isBlank()) root else root.selectFirst(selector) ?: return null
        return when {
            attribute == null -> element.text().trim()
            attribute == "text" -> element.text().trim()
            attribute.isBlank() -> null
            else -> element.attr(attribute).trim()
        }
    }

    private fun resolveUrl(element: Element, value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return java.net.URI(element.baseUri()).resolve(value).toString()
    }
}
