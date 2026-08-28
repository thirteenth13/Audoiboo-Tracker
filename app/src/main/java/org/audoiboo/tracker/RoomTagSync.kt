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

        val playerBooks = PlayerLibrary.all(context)
            .groupBy { it.relativePath.replace('\\', '/').trimEnd('/') }
            .mapValues { (_, tracks) -> tracks.firstOrNull() }

        val dao = AudoibooDatabase.get(context).libraryDao()
        val keys = root.keys()
        while (keys.hasNext()) {
            val dir = keys.next()
            val tagsArray = root.optJSONArray(dir) ?: JSONArray()
            val tags = (0 until tagsArray.length()).mapNotNull { tagsArray.optString(it).takeIf(String::isNotBlank) }
            val playerItem = playerBooks[dir.replace('\\', '/').trimEnd('/')] ?: continue
            val title = normalize(playerItem.bookTitle ?: dir.substringAfterLast('/'))
            val series = normalize(playerItem.series.orEmpty())

            val candidates = tracker.asSequence().flatMap { group ->
                group.books.asSequence().map { book -> group.series.name to book }
            }.filter { (_, book) -> normalize(book.title) == title }.toList()

            val match = candidates.firstOrNull { (seriesName, _) -> series.isBlank() || normalize(seriesName) == series }
                ?: candidates.firstOrNull()
                ?: continue
            dao.setBookTags(match.second.id, tags)
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ")
        .trim()
}
