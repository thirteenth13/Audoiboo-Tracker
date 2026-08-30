package org.audoiboo.tracker

import android.content.Context
import org.audoiboo.tracker.plugin.BookMetadata
import org.audoiboo.tracker.plugin.MetadataProvider
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SourceAuthor
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceCapability
import org.json.JSONObject

/** Enriches canonical books without allowing metadata providers to change source/download identity. */
internal object BookMetadataEnrichment {
    private const val PREFS = "book_metadata_enrichment"

    suspend fun enrichSeries(context: Context, seriesId: String) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val item = dao.seriesWithBooks(seriesId) ?: return
        val providers = PluginPackageRuntime.registry
            .withCapability(SourceCapability.METADATA_ENRICHMENT)
            .mapNotNull { it as? MetadataProvider }
        if (providers.isEmpty()) return

        item.books.forEach { book ->
            val source = SourceBook(
                sourceId = "canonical",
                url = book.url,
                title = book.title,
                authors = book.author?.takeIf(String::isNotBlank)?.let { listOf(SourceAuthor(it)) }.orEmpty(),
                seriesTitle = item.series.name,
                coverUrl = book.coverUrl
            )
            val best = providers.mapNotNull { provider ->
                runCatching { provider.enrichBook(source) }.getOrNull()
            }.maxByOrNull { it.confidence } ?: return@forEach
            if (best.confidence < 0.88f) return@forEach

            persist(context, book.id, best)
            val improved = book.copy(
                // Source title/author stay authoritative unless missing; Open Library mainly fills gaps.
                title = book.title.ifBlank { best.title },
                author = book.author?.takeIf(String::isNotBlank) ?: best.authors.firstOrNull(),
                coverUrl = book.coverUrl?.takeIf(String::isNotBlank) ?: best.coverUrl,
                updatedAt = System.currentTimeMillis()
            )
            if (improved != book) dao.upsertBooks(listOf(improved))
        }
    }

    fun read(context: Context, bookId: String): BookMetadata? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(bookId, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val authors = json.optJSONArray("authors")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            BookMetadata(
                providerId = json.getString("providerId"),
                remoteId = json.getString("remoteId"),
                title = json.getString("title"),
                authors = authors,
                coverUrl = json.optString("coverUrl").takeIf(String::isNotBlank),
                description = json.optString("description").takeIf(String::isNotBlank),
                firstPublishYear = json.optInt("firstPublishYear", 0).takeIf { it > 0 },
                isbn = json.optString("isbn").takeIf(String::isNotBlank),
                confidence = json.optDouble("confidence", 0.0).toFloat()
            )
        }.getOrNull()
    }

    private fun persist(context: Context, bookId: String, metadata: BookMetadata) {
        val authors = org.json.JSONArray().apply { metadata.authors.forEach(::put) }
        val json = JSONObject()
            .put("providerId", metadata.providerId)
            .put("remoteId", metadata.remoteId)
            .put("title", metadata.title)
            .put("authors", authors)
            .put("coverUrl", metadata.coverUrl)
            .put("description", metadata.description)
            .put("firstPublishYear", metadata.firstPublishYear)
            .put("isbn", metadata.isbn)
            .put("confidence", metadata.confidence.toDouble())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(bookId, json.toString()).apply()
    }
}
