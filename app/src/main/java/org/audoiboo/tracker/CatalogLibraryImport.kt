package org.audoiboo.tracker

import android.content.Context
import org.audoiboo.tracker.plugin.CatalogAudioSourceSelector
import org.audoiboo.tracker.plugin.CatalogSourceMatch
import org.audoiboo.tracker.plugin.MatchDisposition

internal sealed interface CatalogLibraryImportResult {
    data class Added(val seriesId: String, val name: String, val books: Int) : CatalogLibraryImportResult
    data class NeedsReview(val sourceUrl: String, val confidence: Float) : CatalogLibraryImportResult
    data object NoAudioSource : CatalogLibraryImportResult
}

/**
 * Reuses the existing RoomSeriesSync path instead of creating a second persistence implementation.
 * Catalog identity remains bibliographic; the selected audio source becomes the canonical Room source.
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
}
