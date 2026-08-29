package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object RoomTagSync {
    private const val PREFS = "player_extras"
    private const val KEY = "book_tags"
    private const val MIGRATION_PREFS = "room_migration"
    private const val MIGRATION_KEY = "book_tags_v1"

    suspend fun syncFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val flags = app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(MIGRATION_KEY, false)) return@withContext
        importLegacy(app, overwriteExisting = false)
        flags.edit().putBoolean(MIGRATION_KEY, true).apply()
    }

    /** Explicit old-backup restore path; backup content is authoritative. */
    suspend fun restoreFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        importLegacy(app, overwriteExisting = true)
        app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIGRATION_KEY, true).apply()
    }

    private suspend fun importLegacy(context: Context, overwriteExisting: Boolean) {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val tracker = LibraryRepository.snapshot(context)
        if (tracker.isEmpty()) return
        val playerBooks = playerBooks(context)
        val dao = AudoibooDatabase.get(context).libraryDao()
        val keys = root.keys()
        while (keys.hasNext()) {
            val dir = keys.next()
            val tagsArray = root.optJSONArray(dir) ?: JSONArray()
            val tags = (0 until tagsArray.length()).mapNotNull { tagsArray.optString(it).takeIf(String::isNotBlank) }
            val bookId = resolveBookId(tracker, playerBooks, dir) ?: continue
            if (!overwriteExisting && dao.bookWithTags(bookId)?.tags?.isNotEmpty() == true) continue
            dao.setBookTags(bookId, tags)
        }
    }

    suspend fun tagsForDir(context: Context, dir: String): List<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val tracker = LibraryRepository.snapshot(app)
        val id = resolveBookId(tracker, playerBooks(app), dir) ?: return@withContext emptyList()
        AudoibooDatabase.get(app).libraryDao().bookWithTags(id)?.tags?.map { it.name }.orEmpty()
    }

    suspend fun tagsForDirs(context: Context, dirs: List<String>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val tracker = LibraryRepository.snapshot(app)
        val player = playerBooks(app)
        val dao = AudoibooDatabase.get(app).libraryDao()
        dirs.distinct().associateWith { dir ->
            val id = resolveBookId(tracker, player, dir)
            if (id == null) emptyList() else dao.bookWithTags(id)?.tags?.map { it.name }.orEmpty()
        }
    }

    suspend fun setTagsForDir(context: Context, dir: String, tags: List<String>): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val tracker = LibraryRepository.snapshot(app)
        val id = resolveBookId(tracker, playerBooks(app), dir) ?: return@withContext false
        AudoibooDatabase.get(app).libraryDao().setBookTags(id, tags)
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

    internal fun normalizeDir(value: String) = value.replace('\\', '/').trimEnd('/')

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ")
        .trim()
}
