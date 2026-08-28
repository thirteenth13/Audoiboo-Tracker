package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transition repository for the tracker library.
 * Room is the durable structured store; the legacy JSON preference is dual-written so existing
 * backup/automation code keeps working until those consumers are migrated too.
 */
object LibraryRepository {
    private const val PREFS = "tracker"
    private const val KEY = "library"

    fun observe(context: Context): Flow<List<SeriesWithBooks>> =
        AudoibooDatabase.get(context).libraryDao().observeLibrary()

    suspend fun snapshot(context: Context): List<SeriesWithBooks> = withContext(Dispatchers.IO) {
        LegacyLibraryImporter.importIfNeeded(context)
        AudoibooDatabase.get(context).libraryDao().library()
    }

    suspend fun replaceAll(context: Context, library: List<SeriesWithBooks>) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val current = dao.library().map { it.series.id }.toSet()
        val incoming = library.map { it.series.id }.toSet()
        (current - incoming).forEach { dao.deleteSeries(it) }
        library.forEach { item ->
            dao.upsertSeries(item.series.copy(updatedAt = System.currentTimeMillis()))
            dao.deleteBooksForSeries(item.series.id)
            dao.upsertBooks(item.books.mapIndexed { index, book ->
                book.copy(seriesId = item.series.id, sortIndex = index, updatedAt = System.currentTimeMillis())
            })
        }
        writeLegacy(context, library)
    }

    suspend fun deleteSeries(context: Context, id: String) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        dao.deleteSeries(id)
        writeLegacy(context, dao.library())
    }

    private fun writeLegacy(context: Context, library: List<SeriesWithBooks>) {
        val root = JSONArray()
        library.forEach { item ->
            val series = item.series
            val books = JSONArray()
            item.books.sortedBy { it.sortIndex }.forEach { book ->
                books.put(JSONObject()
                    .put("title", book.title)
                    .put("url", book.url)
                    .put("author", book.author)
                    .put("coverUrl", book.coverUrl)
                    .put("status", book.status)
                    .put("archiveUrl", book.archiveUrl))
            }
            root.put(JSONObject()
                .put("id", series.id)
                .put("name", series.name)
                .put("url", series.url)
                .put("books", books))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }
}
