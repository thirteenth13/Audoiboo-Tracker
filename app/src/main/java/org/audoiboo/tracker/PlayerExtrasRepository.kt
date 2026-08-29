package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ListeningStatsSnapshot(
    val totalMs: Long,
    val daily: List<DailyListeningEntity>
)

/** Room-native read and backup API for history, bookmarks and listening statistics. */
internal object PlayerExtrasRepository {
    fun observeHistory(context: Context): Flow<List<PlaybackHistoryEntity>> =
        AudoibooDatabase.get(context.applicationContext).libraryDao().observePlaybackHistory()

    fun observeBookmarks(context: Context): Flow<List<PlayerBookmarkEntity>> =
        AudoibooDatabase.get(context.applicationContext).libraryDao().observePlayerBookmarks()

    fun observeDaily(context: Context): Flow<List<DailyListeningEntity>> =
        AudoibooDatabase.get(context.applicationContext).libraryDao().observeDailyListening()

    suspend fun history(context: Context): List<PlaybackHistoryEntity> = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context.applicationContext).libraryDao().playbackHistory()
    }

    suspend fun bookmarks(context: Context): List<PlayerBookmarkEntity> = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context.applicationContext).libraryDao().playerBookmarks()
    }

    suspend fun stats(context: Context): ListeningStatsSnapshot = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        ListeningStatsSnapshot(
            totalMs = dao.listeningTotal()?.listenedMs ?: 0L,
            daily = dao.dailyListening()
        )
    }

    suspend fun exportJson(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        val history = JSONArray().apply {
            dao.playbackHistory().forEach { row -> put(JSONObject().put("dir", row.dir).put("title", row.title).put("at", row.at)) }
        }
        val bookmarks = JSONArray().apply {
            dao.playerBookmarks().forEach { row ->
                put(JSONObject().put("id", row.id).put("uri", row.uri).put("positionMs", row.positionMs).put("note", row.note).put("createdAt", row.createdAt))
            }
        }
        val daily = JSONArray().apply {
            dao.dailyListening().forEach { row -> put(JSONObject().put("day", row.day).put("listenedMs", row.listenedMs)) }
        }
        JSONObject()
            .put("history", history)
            .put("bookmarks", bookmarks)
            .put("daily", daily)
            .put("totalMs", dao.listeningTotal()?.listenedMs ?: 0L)
    }

    suspend fun restoreJson(context: Context, json: JSONObject?) = withContext(Dispatchers.IO) {
        if (json == null) return@withContext
        val historyArray = json.optJSONArray("history") ?: JSONArray()
        val bookmarkArray = json.optJSONArray("bookmarks") ?: JSONArray()
        val dailyArray = json.optJSONArray("daily") ?: JSONArray()
        val history = (0 until historyArray.length()).mapNotNull { i ->
            val o = historyArray.optJSONObject(i) ?: return@mapNotNull null
            val dir = o.optString("dir").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PlaybackHistoryEntity(dir, o.optString("title", dir), o.optLong("at", 0L))
        }
        val bookmarks = (0 until bookmarkArray.length()).mapNotNull { i ->
            val o = bookmarkArray.optJSONObject(i) ?: return@mapNotNull null
            val uri = o.optString("uri").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val createdAt = o.optLong("createdAt", 0L)
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: "$uri::$createdAt"
            PlayerBookmarkEntity(id, uri, o.optLong("positionMs", 0L).coerceAtLeast(0L), o.optString("note"), createdAt)
        }
        val daily = (0 until dailyArray.length()).mapNotNull { i ->
            val o = dailyArray.optJSONObject(i) ?: return@mapNotNull null
            val day = o.optString("day").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DailyListeningEntity(day, o.optLong("listenedMs", 0L).coerceAtLeast(0L))
        }
        AudoibooDatabase.get(context.applicationContext).libraryDao().replacePlayerExtras(
            history = history,
            bookmarks = bookmarks,
            daily = daily,
            totalMs = json.optLong("totalMs", 0L).coerceAtLeast(0L)
        )
    }

    /** Ensure a device upgraded from an older build has Room populated before first Room-native read. */
    suspend fun reconcile(context: Context) = PlayerExtrasRoomSync.syncFromLegacy(context.applicationContext)
}
