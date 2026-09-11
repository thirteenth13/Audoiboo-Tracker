package org.audoiboo.tracker

import android.content.Context
import org.audoiboo.tracker.plugin.SourceMetadataDatabase

/**
 * Conservative startup repair for source metadata left by older builds.
 *
 * It intentionally does not guess new book matches. It only removes canonical ids that point to
 * books/series which no longer exist, leaving the source observation itself intact so discovery or
 * manual review can attach it again later.
 */
internal object LegacySourceMetadataRepair {
    suspend fun repair(context: Context): Int {
        val library = AudoibooDatabase.get(context).libraryDao().library()
        val validSeriesIds = library.mapTo(hashSetOf()) { it.series.id }
        val validBookIds = library.flatMapTo(hashSetOf()) { it.books.map { book -> book.id } }
        val dao = SourceMetadataDatabase.get(context).dao()
        var repaired = 0

        dao.allBookSources().forEach { source ->
            val staleBook = source.canonicalBookId?.takeIf { it.isNotBlank() }?.let { it !in validBookIds } == true
            val staleSeries = source.canonicalSeriesId?.takeIf { it.isNotBlank() }?.let { it !in validSeriesIds } == true
            if (!staleBook && !staleSeries) return@forEach

            dao.upsertBookSource(
                source.copy(
                    canonicalBookId = if (staleBook) null else source.canonicalBookId,
                    canonicalSeriesId = if (staleSeries) null else source.canonicalSeriesId
                )
            )
            repaired++
        }
        return repaired
    }
}
