package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class RoomSeriesSyncResult(val seriesId: String, val name: String, val books: Int)

/** Fast Room-native add/update path. Null means the caller should use the WebView fallback. */
internal object RoomSeriesSync {
    suspend fun sync(context: Context, inputUrl: String): RoomSeriesSyncResult? = withContext(Dispatchers.IO) {
        val resolved = AudiobooFastParser.resolveSeries(inputUrl) ?: return@withContext null
        val parsed = AudiobooFastParser.parseSeries(resolved.url) ?: return@withContext null
        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        val result = db.withTransaction {
            val existing = dao.seriesByUrl(resolved.url)
            val id = existing?.id ?: UUID.randomUUID().toString()
            val old = dao.library().firstOrNull { it.series.id == id }?.books?.associateBy { it.url }.orEmpty()
            val now = System.currentTimeMillis()
            val books = parsed.mapIndexed { index, fast ->
                val previous = old[fast.url]
                BookEntity(
                    id = "$id::${fast.url}",
                    seriesId = id,
                    title = fast.title,
                    url = fast.url,
                    author = fast.author ?: previous?.author,
                    coverUrl = fast.coverUrl ?: previous?.coverUrl,
                    status = previous?.status ?: "NEW",
                    archiveUrl = previous?.archiveUrl,
                    sortIndex = index,
                    updatedAt = now
                )
            }
            dao.upsertSeries(SeriesEntity(id, resolved.name, resolved.url, now))
            if (books.isEmpty()) dao.deleteBooksForSeries(id)
            else {
                dao.deleteMissingBooks(id, books.map { it.id })
                dao.upsertBooks(books)
            }
            RoomSeriesSyncResult(id, resolved.name, books.size)
        }
        LibraryRepository.mirrorLegacy(context)
        RoomCoverSync.enqueueAll(context)
        result
    }
}
