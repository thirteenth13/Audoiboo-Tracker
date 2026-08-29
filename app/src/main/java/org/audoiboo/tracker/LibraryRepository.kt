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

enum class RoomBookFilter(val status: String = "", val tagMode: Int = 0) {
    ALL,
    NEW("NEW"),
    READING("READING"),
    READ("READ"),
    TAGGED(tagMode = 1),
    UNTAGGED(tagMode = 2)
}

object LibraryRepository {
    private const val PREFS = "tracker"
    private const val KEY = "library"

    fun observe(context: Context): Flow<List<SeriesWithBooks>> = AudoibooDatabase.get(context).libraryDao().observeLibrary()
    fun observeTags(context: Context): Flow<List<TagEntity>> = AudoibooDatabase.get(context).libraryDao().observeTags()

    fun pagedBooks(context: Context, query: String = "", filter: RoomBookFilter = RoomBookFilter.ALL): Flow<PagingData<BookEntity>> {
        val dao = AudoibooDatabase.get(context).libraryDao()
        return Pager(PagingConfig(pageSize = 30, prefetchDistance = 10, enablePlaceholders = false)) {
            dao.pagedFilteredBooks(query.trim(), filter.status, filter.tagMode)
        }.flow
    }

    suspend fun bookWithTags(context: Context, bookId: String): BookWithTags? = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context).libraryDao().bookWithTags(bookId)
    }

    suspend fun setBookTags(context: Context, bookId: String, tags: List<String>) = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context).libraryDao().setBookTags(bookId, tags)
    }

    suspend fun exportTagsJson(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        val out = JSONObject()
        dao.library().flatMap { it.books }.forEach { book ->
            val tags = dao.bookWithTags(book.id)?.tags.orEmpty()
            if (tags.isNotEmpty()) {
                val arr = JSONArray()
                tags.map { it.name }.distinctBy { it.lowercase() }.forEach(arr::put)
                out.put(book.id, arr)
            }
        }
        out
    }

    suspend fun restoreTagsJson(context: Context, root: JSONObject?) = withContext(Dispatchers.IO) {
        if (root == null) return@withContext
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        val existing = dao.library().flatMap { it.books }.map { it.id }.toSet()
        val keys = root.keys()
        while (keys.hasNext()) {
            val bookId = keys.next()
            if (bookId !in existing) continue
            val arr = root.optJSONArray(bookId) ?: JSONArray()
            val tags = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            dao.setBookTags(bookId, tags)
        }
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

    suspend fun exportCompatJson(context: Context): String = withContext(Dispatchers.IO) {
        LegacyLibraryImporter.importIfNeeded(context)
        legacyJson(AudoibooDatabase.get(context).libraryDao().library())
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

    fun isValidLegacyJson(raw: String): Boolean = runCatching {
        val root = JSONArray(raw)
        val seriesIds = HashSet<String>()
        val bookUrls = HashSet<String>()
        for (i in 0 until root.length()) {
            val series = root.optJSONObject(i) ?: return@runCatching false
            val id = series.optString("id").takeIf { it.isNotBlank() } ?: return@runCatching false
            if (!seriesIds.add(id)) return@runCatching false
            if (series.has("books") && series.opt("books") !is JSONArray) return@runCatching false
            val books = series.optJSONArray("books") ?: JSONArray()
            for (j in 0 until books.length()) {
                val book = books.optJSONObject(j) ?: return@runCatching false
                val url = book.optString("url").takeIf { it.isNotBlank() } ?: return@runCatching false
                if (!bookUrls.add(url)) return@runCatching false
            }
        }
        true
    }.getOrDefault(false)

    suspend fun restoreLegacyJson(context: Context, raw: String) = withContext(Dispatchers.IO) {
        require(isValidLegacyJson(raw)) { "Backup tracker data is invalid" }
        val root = JSONArray(raw)
        val library = (0 until root.length()).map { i ->
            val s = root.getJSONObject(i)
            val id = s.getString("id")
            val series = SeriesEntity(id, s.optString("name"), s.optString("url"))
            val arr = s.optJSONArray("books") ?: JSONArray()
            val books = (0 until arr.length()).map { j ->
                val b = arr.getJSONObject(j)
                val url = b.getString("url")
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
