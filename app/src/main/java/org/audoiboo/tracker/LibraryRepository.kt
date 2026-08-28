package org.audoiboo.tracker

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object LibraryRepository {
    private const val PREFS = "tracker"
    private const val KEY = "library"

    fun observe(context: Context): Flow<List<SeriesWithBooks>> = AudoibooDatabase.get(context).libraryDao().observeLibrary()

    fun pagedBooks(context: Context, query: String = ""): Flow<PagingData<BookEntity>> {
        val dao = AudoibooDatabase.get(context).libraryDao()
        return Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, enablePlaceholders = false)) {
            if (query.isBlank()) dao.pagedBooks() else dao.searchBooks(query.trim())
        }.flow
    }

    suspend fun snapshot(context: Context): List<SeriesWithBooks> = withContext(Dispatchers.IO) {
        LegacyLibraryImporter.importIfNeeded(context)
        AudoibooDatabase.get(context).libraryDao().library()
    }

    suspend fun replaceAll(context: Context, library: List<SeriesWithBooks>) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        dao.replaceLibrary(library)
        writeLegacy(context, dao.library())
    }

    suspend fun restoreLegacyJson(context: Context, raw: String) = withContext(Dispatchers.IO) {
        val root = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val library = (0 until root.length()).mapNotNull { i ->
            val s = root.optJSONObject(i) ?: return@mapNotNull null
            val id = s.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val series = SeriesEntity(id, s.optString("name"), s.optString("url"))
            val arr = s.optJSONArray("books") ?: JSONArray()
            val books = (0 until arr.length()).mapNotNull { j ->
                val b = arr.optJSONObject(j) ?: return@mapNotNull null
                val url = b.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BookEntity(
                    id = "$id::$url", seriesId = id, title = b.optString("title"), url = url,
                    author = b.optString("author").takeIf { it.isNotBlank() && it != "null" },
                    coverUrl = b.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
                    status = b.optString("status", "NEW"),
                    archiveUrl = b.optString("archiveUrl").takeIf { it.isNotBlank() && it != "null" },
                    sortIndex = j
                )
            }
            SeriesWithBooks(series, books)
        }
        val dao = AudoibooDatabase.get(context).libraryDao()
        dao.replaceLibrary(library)
        writeLegacy(context, dao.library())
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
                books.put(JSONObject().put("title", book.title).put("url", book.url).put("author", book.author)
                    .put("coverUrl", book.coverUrl).put("status", book.status).put("archiveUrl", book.archiveUrl))
            }
            root.put(JSONObject().put("id", series.id).put("name", series.name).put("url", series.url).put("books", books))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, root.toString()).apply()
    }
}
