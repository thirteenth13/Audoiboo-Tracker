package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import org.audoiboo.tracker.plugin.CatalogAudioSourceSelector
import org.audoiboo.tracker.plugin.CatalogSourceMatch
import org.audoiboo.tracker.plugin.MatchDisposition

internal sealed interface CatalogLibraryImportResult {
    data class Added(val seriesId: String, val name: String, val books: Int) : CatalogLibraryImportResult
    data class NeedsReview(val sourceUrl: String, val confidence: Float) : CatalogLibraryImportResult
    data object NoAudioSource : CatalogLibraryImportResult
}

/**
 * Imports catalog matches into the Room library.
 * When an audio source is available we keep using RoomSeriesSync so the audio source remains canonical.
 * Catalog-only import stores a stable synthetic catalog URL and can be enriched with audio later.
 */
internal object CatalogLibraryImport {
    suspend fun add(
        context: Context,
        match: CatalogSourceMatch,
        preferredSourceId: String? = null
    ): CatalogLibraryImportResult {
        val accepted = CatalogAudioSourceSelector.select(match, preferredSourceId)?.finding

        if (accepted != null) {
            val synced = RoomSeriesSync.sync(context, accepted.series.url)
            if (synced?.seriesId != null) {
                return CatalogLibraryImportResult.Added(synced.seriesId, synced.name, synced.books)
            }
        }

        // When a user explicitly selected a source, do not silently redirect them to a different
        // review candidate. The UI can keep the user on the catalog card and let them choose again.
        if (!preferredSourceId.isNullOrBlank()) return CatalogLibraryImportResult.NoAudioSource

        val review = match.sources
            .filter { it.disposition == MatchDisposition.REVIEW }
            .maxByOrNull { it.confidence }
        if (review != null) {
            return CatalogLibraryImportResult.NeedsReview(review.series.url, review.confidence)
        }

        return CatalogLibraryImportResult.NoAudioSource
    }

    suspend fun addCatalogOnly(
        context: Context,
        match: CatalogSourceMatch
    ): CatalogLibraryImportResult.Added {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val seriesId = "catalog::${match.canonical.id}"
        val seriesUrl = "catalog://${match.catalogProviderId}/series/${Uri.encode(match.canonical.id)}"
        val previous = dao.seriesWithBooks(seriesId)
        val previousBooks = previous?.books.orEmpty().associateBy { it.id }
        val now = System.currentTimeMillis()

        dao.upsertSeries(
            SeriesEntity(
                id = seriesId,
                name = match.series.title,
                url = seriesUrl,
                updatedAt = now
            )
        )

        val books = match.series.books.mapIndexed { index, book ->
            val bookId = "$seriesId::${book.providerId}:${book.remoteId}"
            val old = previousBooks[bookId]
            BookEntity(
                id = bookId,
                seriesId = seriesId,
                title = book.title,
                url = "catalog://${book.providerId}/book/${Uri.encode(book.remoteId)}",
                author = book.authors.takeIf { it.isNotEmpty() }?.joinToString() ?: match.author.name.takeIf { it.isNotBlank() },
                coverUrl = book.coverUrl ?: old?.coverUrl,
                status = old?.status ?: "NEW",
                archiveUrl = old?.archiveUrl,
                sortIndex = index,
                updatedAt = now
            )
        }

        if (books.isEmpty()) {
            dao.deleteBooksForSeries(seriesId)
        } else {
            dao.deleteMissingBooks(seriesId, books.map { it.id })
            dao.upsertBooks(books)
        }

        return CatalogLibraryImportResult.Added(seriesId, match.series.title, books.size)
    }
}
