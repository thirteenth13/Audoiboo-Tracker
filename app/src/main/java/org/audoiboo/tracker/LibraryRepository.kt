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
    fun observeTags(context: Context): Flow<List<TagEntity>> = AudoibooDatabase.get(context).libraryDao().observeTags()

    fun pagedBooks(context: Context, query: String = ""): Flow<PagingData<BookEntity>> {
        val dao = AudoibooDatabase.get(context).libraryDao()
        return Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, enablePlaceholders = false)) {
            if (query.isBlank()) dao.pagedBooks() else dao.searchBooksAndTags(query.trim())
        }.flow
    }

    suspend fun bookWithTags(context: Context, bookId: String): BookWithTags? = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context).libraryDao().bookWithTags(bookId)
    }

    suspend fun setBookTags(context: Context, bookId: String, tags: List<String>) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        dao.setBookTags(bookId, tags)
    }

    suspend fun updateBookStatus(context: Context, bookId: String, status: String) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        dao.updateBookStatus(bookId, status)
        writeLegacy(context, dao.library())
    }

    suspend fun updateBookArchive(context: Context, bookId: String, archiveUrl: String?) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        dao.updateBookArchive(bookId, archiveUrl)
        writeLegacy(context, dao.library())
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

    suspend fun mirrorLegacy(context: Context, library: List<SeriesWithBooks>? = null): String = withContext(Dispatchers.IO) {
        val items = library ?: AudoibooDatabase.get(context).libraryDao().library()
        legacyJson(items).also { raw ->
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY, null) != raw) prefs.edit().putString(KEY, raw).apply()
        }
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
                    status = b.optString("status", "NEW"), archiveUrl = b.optString("archiveUrl").takeIf { it.isNotBlank() && it != "null" }, sortIndex = j
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
        val raw = legacyJson(library)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY, null) != raw) prefs.edit().putString(KEY, raw).apply()
    }

    private fun legacyJson(library: List<SeriesWithBooks>): String {
        val root = JSONArray()
        library.forEach { item ->
            val books = JSONArray()
            item.books.sortedBy { it.sortIndex }.forEach { book ->
                books.put(JSONObject().put("title", book.title).put("url", book.url).put("author", book.author)
                    .put("coverUrl", book.coverUrl).put("status", book.status).put("archiveUrl", book.archiveUrl))
            }
            root.put(JSONObject().put("id", item.series.id).put("name", item.series.name).put("url", item.series.url).put("books", books))
        }
        return root.toString()
    }
}
