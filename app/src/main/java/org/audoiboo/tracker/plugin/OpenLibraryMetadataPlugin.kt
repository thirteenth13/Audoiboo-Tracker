package org.audoiboo.tracker.plugin

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.net.URI

/** Low-volume metadata enrichment for books already discovered by audio-source plugins. */
object OpenLibraryMetadataPlugin : SourcePlugin, MetadataProvider {
    override val descriptor = SourceDescriptor(
        id = "open-library",
        name = "Open Library Metadata",
        version = 1,
        hosts = setOf("openlibrary.org", "covers.openlibrary.org"),
        capabilities = setOf(SourceCapability.METADATA_ENRICHMENT)
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
        val response = HostPluginHttpTransport.get(
            PluginHttpRequest(query, mapOf("Accept" to "application/json")),
            2L * 1024 * 1024
        )
        if (response.statusCode !in 200..299) return null
        val docs = JSONObject(response.body).optJSONArray("docs") ?: return null
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

            val authors = item.optJSONArray("author_name")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf(String::isNotBlank) }
            }.orEmpty()
            val authorScore = when {
                expectedAuthor.isBlank() -> 0.7f
                authors.any { normalize(it) == expectedAuthor } -> 1f
                authors.any { normalize(it).contains(expectedAuthor) || expectedAuthor.contains(normalize(it)) } -> 0.82f
                else -> 0f
            }
            val confidence = titleScore * 0.72f + authorScore * 0.28f
            if (confidence < 0.88f) continue

            val key = item.optString("key").trim().removePrefix("/works/")
            if (key.isBlank()) continue
            val coverId = item.optLong("cover_i", -1L).takeIf { it > 0 }
            val isbn = item.optJSONArray("isbn")?.optString(0)?.takeIf(String::isNotBlank)
            val metadata = BookMetadata(
                providerId = descriptor.id,
                remoteId = key,
                title = remoteTitle,
                authors = authors,
                coverUrl = coverId?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg?default=false" },
                firstPublishYear = item.optInt("first_publish_year", 0).takeIf { it > 0 },
                isbn = isbn,
                confidence = confidence
            )
            if (best == null || metadata.confidence > best!!.confidence) best = metadata
        }
        return best
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun normalize(value: String): String = SourceIdentityMatcher.normalizeTitle(value)
}
