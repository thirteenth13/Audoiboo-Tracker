package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object RoomTagSync {
    private const val PREFS = "player_extras"
    private const val KEY = "book_tags"

    suspend fun syncFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@withContext
        val tracker = LibraryRepository.snapshot(context)
        if (tracker.isEmpty()) return@withContext
        val playerBooks = playerBooks(context)
        val dao = AudoibooDatabase.get(context).libraryDao()
        val keys = root.keys()
        while (keys.hasNext()) {
            val dir = keys.next()
            val tagsArray = root.optJSONArray(dir) ?: JSONArray()
            val tags = (0 until tagsArray.length()).mapNotNull { tagsArray.optString(it).takeIf(String::isNotBlank) }
            val bookId = resolveBookId(tracker, playerBooks, dir) ?: continue
            dao.setBookTags(bookId, tags)
        }
    }

    suspend fun tagsForDir(context: Context, dir: String): List<String> = withContext(Dispatchers.IO) {
        val tracker = LibraryRepository.snapshot(context)
        val id = resolveBookId(tracker, playerBooks(context), dir) ?: return@withContext emptyList()
        AudoibooDatabase.get(context).libraryDao().bookWithTags(id)?.tags?.map { it.name }.orEmpty()
    }

    suspend fun tagsForDirs(context: Context, dirs: List<String>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val tracker = LibraryRepository.snapshot(context)
        val player = playerBooks(context)
        val dao = AudoibooDatabase.get(context).libraryDao()
        dirs.distinct().associateWith { dir ->
            val id = resolveBookId(tracker, player, dir)
            if (id == null) emptyList() else dao.bookWithTags(id)?.tags?.map { it.name }.orEmpty()
        }
    }

    suspend fun setTagsForDir(context: Context, dir: String, tags: List<String>): Boolean = withContext(Dispatchers.IO) {
        val tracker = LibraryRepository.snapshot(context)
        val id = resolveBookId(tracker, playerBooks(context), dir) ?: return@withContext false
        AudoibooDatabase.get(context).libraryDao().setBookTags(id, tags)
        true
    }

    private fun playerBooks(context: Context): Map<String, PlayerLibraryItem?> = PlayerLibrary.all(context)
        .groupBy { normalizeDir(it.relativePath) }
        .mapValues { (_, tracks) -> tracks.firstOrNull() }

    private fun resolveBookId(
        tracker: List<SeriesWithBooks>,
        playerBooks: Map<String, PlayerLibraryItem?>,
        dir: String
    ): String? {
        val playerItem = playerBooks[normalizeDir(dir)] ?: return null
        val title = normalize(playerItem.bookTitle ?: dir.substringAfterLast('/'))
        val series = normalize(playerItem.series.orEmpty())
        val candidates = tracker.asSequence().flatMap { group ->
            group.books.asSequence().map { book -> group.series.name to book }
        }.filter { (_, book) -> normalize(book.title) == title }.toList()
        return (candidates.firstOrNull { (seriesName, _) -> series.isBlank() || normalize(seriesName) == series }
            ?: candidates.firstOrNull())?.second?.id
    }

    private fun normalizeDir(value: String) = value.replace('\\', '/').trimEnd('/')

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ")
        .trim()
}
