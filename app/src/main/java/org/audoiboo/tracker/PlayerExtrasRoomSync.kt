package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One-time import of pre-Room player history, bookmarks and listening statistics. */
internal object PlayerExtrasRoomSync {
    private const val PREFS = "player_extras"
    private const val MIGRATION_PREFS = "room_migration"
    private const val MIGRATION_KEY = "player_extras_v1"

    suspend fun syncFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val flags = app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(MIGRATION_KEY, false)) return@withContext

        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val history = parseHistory(prefs.getString("history", "[]").orEmpty())
        val bookmarks = parseBookmarks(prefs.getString("bookmarks_v2", "[]").orEmpty())
        val daily = parseDaily(prefs.getString("daily_listened", "{}").orEmpty())
        val totalMs = prefs.getLong("listened_ms", 0L).coerceAtLeast(0L)
        val dao = AudoibooDatabase.get(app).libraryDao()

        if (dao.playbackHistory().isEmpty() && history.isNotEmpty()) dao.upsertPlaybackHistory(history)
        if (dao.playerBookmarks().isEmpty() && bookmarks.isNotEmpty()) dao.upsertPlayerBookmarks(bookmarks)
        if (dao.dailyListening().isEmpty() && daily.isNotEmpty()) dao.upsertDailyListening(daily)
        if ((dao.listeningTotal()?.listenedMs ?: 0L) <= 0L && totalMs > 0L) {
            dao.upsertListeningTotal(ListeningTotalEntity(listenedMs = totalMs))
        }
        flags.edit().putBoolean(MIGRATION_KEY, true).apply()
    }

    /** Explicit import path for old backup formats. */
    suspend fun restoreFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        AudoibooDatabase.get(app).libraryDao().replacePlayerExtras(
            history = parseHistory(prefs.getString("history", "[]").orEmpty()),
            bookmarks = parseBookmarks(prefs.getString("bookmarks_v2", "[]").orEmpty()),
            daily = parseDaily(prefs.getString("daily_listened", "{}").orEmpty()),
            totalMs = prefs.getLong("listened_ms", 0L).coerceAtLeast(0L)
        )
        app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIGRATION_KEY, true).apply()
    }

    fun clearMigratedLegacy(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("history")
            .remove("bookmarks_v2")
            .remove("daily_listened")
            .remove("listened_ms")
            .apply()
    }

    private fun parseHistory(raw: String): List<PlaybackHistoryEntity> = runCatching {
        val a = JSONArray(raw.ifBlank { "[]" })
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val dir = o.optString("dir").takeIf(String::isNotBlank) ?: return@mapNotNull null
            PlaybackHistoryEntity(dir, o.optString("title", dir), o.optLong("at", 0L))
        }.sortedByDescending { it.at }.distinctBy { it.dir }.take(50)
    }.getOrDefault(emptyList())

    private fun parseBookmarks(raw: String): List<PlayerBookmarkEntity> = runCatching {
        val a = JSONArray(raw.ifBlank { "[]" })
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val position = o.optLong("position", 0L).coerceAtLeast(0L)
            val createdAt = o.optLong("createdAt", 0L)
            PlayerBookmarkEntity(
                id = bookmarkId(uri, position, createdAt),
                uri = uri,
                positionMs = position,
                note = o.optString("note"),
                createdAt = createdAt
            )
        }.sortedByDescending { it.createdAt }.take(500)
    }.getOrDefault(emptyList())

    private fun parseDaily(raw: String): List<DailyListeningEntity> = runCatching {
        val o = JSONObject(raw.ifBlank { "{}" })
        o.keys().asSequence().map { day -> DailyListeningEntity(day, o.optLong(day, 0L).coerceAtLeast(0L)) }
            .sortedByDescending { it.day }.take(120).toList()
    }.getOrDefault(emptyList())

    private fun bookmarkId(uri: String, position: Long, createdAt: Long): String = "$createdAt|$position|$uri"
}
