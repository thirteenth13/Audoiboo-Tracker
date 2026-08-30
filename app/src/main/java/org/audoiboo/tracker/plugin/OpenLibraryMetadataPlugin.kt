package org.audoiboo.tracker.plugin

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Low-volume metadata and author-catalog discovery backed by Open Library. */
object OpenLibraryMetadataPlugin : SourcePlugin, MetadataProvider, AuthorCatalogProvider {
    override val descriptor = SourceDescriptor(
        id = "open-library",
        name = "Open Library Metadata",
        version = 2,
        hosts = setOf("openlibrary.org", "covers.openlibrary.org"),
        capabilities = setOf(SourceCapability.METADATA_ENRICHMENT, SourceCapability.AUTHOR_CATALOG)
    )

    override fun supports(url: String): Boolean = runCatching {
        URI(url).host?.lowercase()?.trimEnd('.') in descriptor.hosts
    }.getOrDefault(false)

    override suspend fun enrichBook(book: SourceBook): BookMetadata? {
        val title = book.title.trim()
        if (title.isBlank()) return null
        val author = book.authors.firstOrNull()?.name?.trim().orEmpty()
        val query = buildString {
            append("https://openlibrary.org/search.json?limit=5&fields=key,title,author_name,cover_i,first_publish_year,isbn")
            append("&title=").append(encode(title))
            if (author.isNotBlank()) append("&author=").append(encode(author))
        }
        val response = getJson(query, 2L * 1024 * 1024) ?: return null
        val docs = response.optJSONArray("docs") ?: return null
        val expectedTitle = SourceIdentityMatcher.normalizeTitle(title)
        val expectedAuthor = normalize(author)
        var best: BookMetadata? = null

        for (i in 0 until docs.length()) {
            val item = docs.optJSONObject(i) ?: continue
            val remoteTitle = item.optString("title").trim()
            val normalizedTitle = SourceIdentityMatcher.normalizeTitle(remoteTitle)
            if (normalizedTitle.isBlank()) continue
            val titleScore = when {
                normalizedTitle == expectedTitle -> 1f
                normalizedTitle.contains(expectedTitle) || expectedTitle.contains(normalizedTitle) -> 0.82f
                else -> 0f
            }
            if (titleScore == 0f) continue

            val authors = strings(item, "author_name")
            val authorScore = when {
                expectedAuthor.isBlank() -> 0.7f
                authors.any { normalize(it) == expectedAuthor } -> 1f
                authors.any { normalize(it).contains(expectedAuthor) || expectedAuthor.contains(normalize(it)) } -> 0.82f
                else -> 0f
            }
            val confidence = titleScore * 0.72f + authorScore * 0.28f
            if (confidence < 0.88f) continue

            val key = item.optString("key").trim().substringAfterLast('/')
            if (key.isBlank()) continue
            val coverId = item.optLong("cover_i", -1L).takeIf { it > 0 }
            val isbn = item.optJSONArray("isbn")?.optString(0)?.takeIf(String::isNotBlank)
            val metadata = BookMetadata(
                providerId = descriptor.id,
                remoteId = key,
                title = remoteTitle,
                authors = authors,
                coverUrl = coverId?.let { coverUrl(it) },
                firstPublishYear = item.optInt("first_publish_year", 0).takeIf { it > 0 },
                isbn = isbn,
                confidence = confidence
            )
            if (best == null || metadata.confidence > best!!.confidence) best = metadata
        }
        return best
    }

    override suspend fun searchAuthors(query: String, limit: Int): List<CatalogAuthor> {
        val expected = normalize(query)
        if (expected.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 20)
        val json = getJson(
            "https://openlibrary.org/search/authors.json?q=${encode(query.trim())}&limit=$safeLimit",
            1024L * 1024L
        ) ?: return emptyList()
        val docs = json.optJSONArray("docs") ?: return emptyList()
        return buildList {
            for (i in 0 until docs.length()) {
                val item = docs.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val key = item.optString("key").trim().substringAfterLast('/')
                if (name.isBlank() || key.isBlank()) continue
                val alternatives = strings(item, "alternate_names")
                val candidates = listOf(name) + alternatives
                val confidence = candidates.maxOfOrNull { authorConfidence(expected, normalize(it)) } ?: 0f
                if (confidence < 0.55f) continue
                add(
                    CatalogAuthor(
                        providerId = descriptor.id,
                        remoteId = key,
                        name = name,
                        alternativeNames = alternatives,
                        workCount = item.optInt("work_count", -1).takeIf { it >= 0 },
                        confidence = confidence
                    )
                )
            }
        }.sortedByDescending { it.confidence }
    }

    override suspend fun loadAuthorCatalog(author: CatalogAuthor, limit: Int): AuthorCatalog {
        require(author.providerId == descriptor.id) { "Author belongs to another catalog provider" }
        val safeLimit = limit.coerceIn(1, 500)
        val authorKey = author.remoteId.substringAfterLast('/')
        val url = buildString {
            append("https://openlibrary.org/search.json?q=author_key:").append(encode(authorKey))
            append("&fields=key,title,author_name,cover_i,first_publish_year,series")
            append("&limit=").append(safeLimit)
        }
        val json = getJson(url, 8L * 1024 * 1024) ?: return AuthorCatalog(author, emptyList())
        val docs = json.optJSONArray("docs") ?: return AuthorCatalog(author, emptyList())
        val books = buildList {
            for (i in 0 until docs.length()) {
                val item = docs.optJSONObject(i) ?: continue
                val key = item.optString("key").trim().substringAfterLast('/')
                val title = item.optString("title").trim()
                if (key.isBlank() || title.isBlank()) continue
                val coverId = item.optLong("cover_i", -1L).takeIf { it > 0 }
                val explicitSeries = strings(item, "series")
                val inferred = CatalogSeriesHeuristics.infer(title)
                add(
                    CatalogBook(
                        providerId = descriptor.id,
                        remoteId = key,
                        title = title,
                        authors = strings(item, "author_name").ifEmpty { listOf(author.name) },
                        seriesTitles = explicitSeries.ifEmpty { inferred?.let { listOf(it.title) }.orEmpty() },
                        seriesNumber = inferred?.number,
                        firstPublishYear = item.optInt("first_publish_year", 0).takeIf { it > 0 },
                        coverUrl = coverId?.let { coverUrl(it) }
                    )
                )
            }
        }.distinctBy { it.remoteId }
        return AuthorCatalog(author, books)
    }

    private suspend fun getJson(url: String, maxBytes: Long): JSONObject? {
        val response = HostPluginHttpTransport.get(
            PluginHttpRequest(url, mapOf("Accept" to "application/json")),
            maxBytes
        )
        if (response.statusCode !in 200..299) return null
        return runCatching { JSONObject(response.body) }.getOrNull()
    }

    private fun strings(json: JSONObject, name: String): List<String> =
        json.optJSONArray(name)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotBlank) }
        }.orEmpty()

    private fun authorConfidence(expected: String, candidate: String): Float = when {
        candidate == expected -> 1f
        candidate.contains(expected) || expected.contains(candidate) -> 0.86f
        expected.split(' ').filter(String::isNotBlank).toSet().let { tokens ->
            tokens.isNotEmpty() && tokens.all { it in candidate }
        } -> 0.76f
        else -> 0f
    }

    private fun coverUrl(id: Long): String = "https://covers.openlibrary.org/b/id/$id-L.jpg?default=false"
    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun normalize(value: String): String = SourceIdentityMatcher.normalizeTitle(value)
}
