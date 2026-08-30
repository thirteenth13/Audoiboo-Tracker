package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.CanonicalSourceBookLink
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SeriesProvider
import org.audoiboo.tracker.plugin.SourceMetadataRepository
import java.util.UUID

internal data class RoomSeriesSyncResult(val seriesId: String, val name: String, val books: Int)

/**
 * Room-native add/update path routed through the active source plugin registry.
 * Null means no enabled source can handle the URL or the source failed to resolve it.
 */
internal object RoomSeriesSync {
    suspend fun sync(context: Context, inputUrl: String): RoomSeriesSyncResult? = withContext(Dispatchers.IO) {
        PluginPackageRuntime.initialize(context.filesDir)
        val plugin = PluginPackageRuntime.registry.forUrl(inputUrl) ?: return@withContext null
        val provider = plugin as? SeriesProvider ?: return@withContext null
        val resolved = provider.resolveSeries(inputUrl) ?: return@withContext null
        if (resolved.sourceId != plugin.descriptor.id) return@withContext null
        val sourceBooks = provider.loadSeriesBooks(resolved)
        if (sourceBooks.any { it.sourceId != plugin.descriptor.id }) return@withContext null

        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        val result = db.withTransaction {
            val existing = dao.seriesByUrl(resolved.url)
            val id = existing?.id ?: UUID.randomUUID().toString()
            val old = dao.library().firstOrNull { it.series.id == id }?.books?.associateBy { it.url }.orEmpty()
            val now = System.currentTimeMillis()
            val books = sourceBooks.mapIndexed { index, source ->
                val previous = old[source.url]
                BookEntity(
                    id = "$id::${source.url}",
                    seriesId = id,
                    title = source.title,
                    url = source.url,
                    author = source.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() } ?: previous?.author,
                    coverUrl = source.coverUrl ?: previous?.coverUrl,
                    status = previous?.status ?: "NEW",
                    archiveUrl = previous?.archiveUrl,
                    sortIndex = index,
                    updatedAt = now
                )
            }
            dao.upsertSeries(SeriesEntity(id, resolved.title, resolved.url, now))
            if (books.isEmpty()) dao.deleteBooksForSeries(id)
            else {
                dao.deleteMissingBooks(id, books.map { it.id })
                dao.upsertBooks(books)
            }
            RoomSeriesSyncResult(id, resolved.title, books.size)
        }

        SourceMetadataRepository.recordSeriesSnapshot(
            context = context,
            canonicalSeriesId = result.seriesId,
            series = resolved,
            books = sourceBooks.map { source ->
                CanonicalSourceBookLink(
                    canonicalBookId = "${result.seriesId}::${source.url}",
                    book = source
                )
            }
        )
        LibraryRepository.mirrorLegacy(context)
        RoomCoverSync.enqueueAll(context)
        result
    }
}
