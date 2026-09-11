package org.audoiboo.tracker

import android.content.Context
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object CatalogCanonicalUrlPolicy {
    private const val SERIES_PREFIX = "catalog::"

    fun canonicalSeriesUrl(seriesId: String): String? {
        if (!seriesId.startsWith(SERIES_PREFIX)) return null
        val canonicalId = seriesId.removePrefix(SERIES_PREFIX)
        val separator = canonicalId.indexOf(':')
        if (separator <= 0 || separator == canonicalId.lastIndex) return null
        val providerId = canonicalId.substring(0, separator).trim()
        if (providerId.isBlank()) return null
        return "catalog://$providerId/series/${encodePathSegment(canonicalId)}"
    }

    fun canonicalBookUrl(seriesId: String, bookId: String): String? {
        if (!seriesId.startsWith(SERIES_PREFIX)) return null
        val prefix = "$seriesId::"
        if (!bookId.startsWith(prefix)) return null
        val sourceIdentity = bookId.removePrefix(prefix)
        val separator = sourceIdentity.indexOf(':')
        if (separator <= 0 || separator == sourceIdentity.lastIndex) return null
        val providerId = sourceIdentity.substring(0, separator).trim()
        val remoteId = sourceIdentity.substring(separator + 1).trim()
        if (providerId.isBlank() || remoteId.isBlank()) return null
        return "catalog://$providerId/book/${encodePathSegment(remoteId)}"
    }

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}

/**
 * Repairs catalog-only rows whose canonical synthetic URL was overwritten by an audio provider in
 * older builds. Identity is reconstructed solely from stable catalog IDs; no title/author guesses
 * and no network lookup are involved.
 */
internal object CatalogCanonicalUrlRepair {
    suspend fun repair(context: Context): Int {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val now = System.currentTimeMillis()
        var repaired = 0

        dao.library().forEach { item ->
            val canonicalSeriesUrl = CatalogCanonicalUrlPolicy.canonicalSeriesUrl(item.series.id)
                ?: return@forEach
            if (item.series.url != canonicalSeriesUrl) {
                dao.upsertSeries(item.series.copy(url = canonicalSeriesUrl, updatedAt = now))
                repaired++
            }

            val repairedBooks = item.books.mapNotNull { book ->
                val canonicalBookUrl = CatalogCanonicalUrlPolicy.canonicalBookUrl(item.series.id, book.id)
                    ?: return@mapNotNull null
                if (book.url == canonicalBookUrl) return@mapNotNull null
                repaired++
                book.copy(url = canonicalBookUrl, updatedAt = now)
            }
            if (repairedBooks.isNotEmpty()) dao.upsertBooks(repairedBooks)
        }

        return repaired
    }
}
