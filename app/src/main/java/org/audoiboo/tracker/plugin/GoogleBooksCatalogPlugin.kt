package org.audoiboo.tracker.plugin

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Secondary bibliographic catalog backed by the public Google Books volumes search API. */
object GoogleBooksCatalogPlugin : SourcePlugin, AuthorCatalogProvider {
    override val descriptor = SourceDescriptor(
        id = "google-books",
        name = "Google Books Catalog",
        version = 1,
        hosts = setOf("www.googleapis.com", "books.google.com", "books.googleusercontent.com"),
        capabilities = setOf(SourceCapability.AUTHOR_CATALOG)
    )

    override fun supports(url: String): Boolean = runCatching {
        URI(url).host?.lowercase()?.trimEnd('.') in descriptor.hosts
    }.getOrDefault(false)

    override suspend fun searchAuthors(query: String, limit: Int): List<CatalogAuthor> {
        val expected = normalize(query)
        if (expected.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 10)
        val json = getJson(volumesUrl("inauthor:\"${query.trim()}\"", maxResults = 40, startIndex = 0), 2L * 1024 * 1024)
            ?: return emptyList()
        val counts = linkedMapOf<String, Pair<String, Int>>()
        val items = json.optJSONArray("items") ?: return emptyList()
        for (i in 0 until items.length()) {
            val info = items.optJSONObject(i)?.optJSONObject("volumeInfo") ?: continue
            strings(info, "authors").forEach { name ->
                val normalized = normalize(name)
                val confidence = authorConfidence(expected, normalized)
                if (confidence < 0.55f) return@forEach
                val current = counts[normalized]
                counts[normalized] = name to ((current?.second ?: 0) + 1)
            }
        }
        return counts.entries
            .map { (normalized, value) ->
                CatalogAuthor(
                    providerId = descriptor.id,
                    remoteId = normalized.replace(' ', '-').take(160),
                    name = value.first,
                    workCount = value.second,
                    confidence = authorConfidence(expected, normalized)
                )
            }
            .sortedWith(compareByDescending<CatalogAuthor> { it.confidence }.thenByDescending { it.workCount ?: 0 })
            .take(safeLimit)
    }

    override suspend fun loadAuthorCatalog(author: CatalogAuthor, limit: Int): AuthorCatalog {
        require(author.providerId == descriptor.id) { "Author belongs to another catalog provider" }
        val safeLimit = limit.coerceIn(1, 200)
        val books = mutableListOf<CatalogBook>()
        var startIndex = 0
        while (books.size < safeLimit) {
            val pageSize = minOf(40, safeLimit - books.size)
            val json = getJson(
                volumesUrl("inauthor:\"${author.name}\"", maxResults = pageSize, startIndex = startIndex),
                4L * 1024 * 1024
            ) ?: break
            val page = parseBooks(author, json)
            if (page.isEmpty()) break
            books += page
            if (page.size < pageSize) break
            startIndex += pageSize
        }
        return AuthorCatalog(author, books.distinctBy { it.remoteId }.take(safeLimit))
    }

    internal fun parseBooks(author: CatalogAuthor, json: JSONObject): List<CatalogBook> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optString("id").trim()
                val info = item.optJSONObject("volumeInfo") ?: continue
                val title = info.optString("title").trim()
                if (id.isBlank() || title.isBlank()) continue
                val authors = strings(info, "authors")
                if (authors.isNotEmpty() && authors.none { authorConfidence(normalize(author.name), normalize(it)) >= 0.55f }) continue

                val inferred = CatalogSeriesHeuristics.infer(title)
                val seriesInfo = info.optJSONObject("seriesInfo")
                val orderNumber = seriesInfo?.optJSONArray("volumeSeries")?.let { array ->
                    (0 until array.length()).mapNotNull { index ->
                        array.optJSONObject(index)?.optDouble("orderNumber", Double.NaN)?.takeUnless(Double::isNaN)
                    }.minOrNull()
                }
                val displayNumber = seriesInfo?.optString("bookDisplayNumber")?.trim()?.replace(',', '.')?.toDoubleOrNull()
                val number = orderNumber ?: displayNumber ?: inferred?.number
                val cover = info.optJSONObject("imageLinks")?.let { links ->
                    listOf("extraLarge", "large", "medium", "thumbnail", "smallThumbnail")
                        .firstNotNullOfOrNull { key -> links.optString(key).trim().takeIf(String::isNotBlank) }
                }?.replace("http://", "https://")
                val year = Regex("^(\\d{4})").find(info.optString("publishedDate"))?.groupValues?.getOrNull(1)?.toIntOrNull()

                add(
                    CatalogBook(
                        providerId = descriptor.id,
                        remoteId = id,
                        title = title,
                        authors = authors.ifEmpty { listOf(author.name) },
                        seriesTitles = inferred?.let { listOf(it.title) }.orEmpty(),
                        seriesNumber = number,
                        firstPublishYear = year,
                        coverUrl = cover
                    )
                )
            }
        }
    }

    private suspend fun getJson(url: String, maxBytes: Long): JSONObject? {
        val response = HostPluginHttpTransport.get(
            PluginHttpRequest(url, mapOf("Accept" to "application/json")),
            maxBytes
        )
        if (response.statusCode !in 200..299) return null
        return runCatching { JSONObject(response.body) }.getOrNull()
    }

    private fun volumesUrl(query: String, maxResults: Int, startIndex: Int): String = buildString {
        append("https://www.googleapis.com/books/v1/volumes?q=").append(encode(query))
        append("&printType=books&projection=lite")
        append("&maxResults=").append(maxResults.coerceIn(1, 40))
        append("&startIndex=").append(startIndex.coerceAtLeast(0))
    }

    private fun strings(json: JSONObject, name: String): List<String> =
        json.optJSONArray(name)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotBlank) }
        }.orEmpty()

    private fun authorConfidence(expected: String, candidate: String): Float = when {
        candidate == expected -> 0.95f
        candidate.contains(expected) || expected.contains(candidate) -> 0.82f
        expected.split(' ').filter(String::isNotBlank).toSet().let { tokens ->
            tokens.isNotEmpty() && tokens.all { it in candidate }
        } -> 0.72f
        else -> 0f
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun normalize(value: String): String = SourceIdentityMatcher.normalizeTitle(value)
}
