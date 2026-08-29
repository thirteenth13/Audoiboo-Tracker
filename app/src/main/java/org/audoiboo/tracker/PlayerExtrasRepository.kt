package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
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

        val historyArray = requiredArray(json, "history") ?: JSONArray()
        val bookmarkArray = requiredArray(json, "bookmarks") ?: JSONArray()
        val dailyArray = requiredArray(json, "daily") ?: JSONArray()
        val totalMs = if (!json.has("totalMs") || json.isNull("totalMs")) 0L else
            PlayerExtrasValuePolicy.nonNegativeLong(json.opt("totalMs"))
                ?: throw IllegalArgumentException("Backup total listening time is invalid")

        val history = (0 until historyArray.length()).map { i ->
            val o = historyArray.opt(i) as? JSONObject
                ?: throw IllegalArgumentException("Backup history entry is invalid at index $i")
            val dir = PlayerExtrasValuePolicy.text(o.opt("dir"), allowBlank = false)
                ?: throw IllegalArgumentException("Backup history dir is invalid at index $i")
            val title = if (!o.has("title") || o.isNull("title")) dir else
                PlayerExtrasValuePolicy.text(o.opt("title"))
                    ?: throw IllegalArgumentException("Backup history title is invalid at index $i")
            val at = if (!o.has("at") || o.isNull("at")) 0L else
                PlayerExtrasValuePolicy.nonNegativeLong(o.opt("at"))
                    ?: throw IllegalArgumentException("Backup history timestamp is invalid at index $i")
            PlaybackHistoryEntity(dir, title, at)
        }

        val bookmarks = (0 until bookmarkArray.length()).map { i ->
            val o = bookmarkArray.opt(i) as? JSONObject
                ?: throw IllegalArgumentException("Backup bookmark entry is invalid at index $i")
            val uri = PlayerExtrasValuePolicy.text(o.opt("uri"), allowBlank = false)
                ?: throw IllegalArgumentException("Backup bookmark URI is invalid at index $i")
            val createdAt = if (!o.has("createdAt") || o.isNull("createdAt")) 0L else
                PlayerExtrasValuePolicy.nonNegativeLong(o.opt("createdAt"))
                    ?: throw IllegalArgumentException("Backup bookmark timestamp is invalid at index $i")
            val positionMs = if (!o.has("positionMs") || o.isNull("positionMs")) 0L else
                PlayerExtrasValuePolicy.nonNegativeLong(o.opt("positionMs"))
                    ?: throw IllegalArgumentException("Backup bookmark position is invalid at index $i")
            val note = if (!o.has("note") || o.isNull("note")) "" else
                PlayerExtrasValuePolicy.text(o.opt("note"))
                    ?: throw IllegalArgumentException("Backup bookmark note is invalid at index $i")
            val id = if (!o.has("id") || o.isNull("id")) "$uri::$createdAt" else
                PlayerExtrasValuePolicy.text(o.opt("id"), allowBlank = false)
                    ?: throw IllegalArgumentException("Backup bookmark id is invalid at index $i")
            PlayerBookmarkEntity(id, uri, positionMs, note, createdAt)
        }

        val daily = (0 until dailyArray.length()).map { i ->
            val o = dailyArray.opt(i) as? JSONObject
                ?: throw IllegalArgumentException("Backup daily listening entry is invalid at index $i")
            val day = PlayerExtrasValuePolicy.validDay(o.opt("day"))
                ?: throw IllegalArgumentException("Backup listening day is invalid at index $i")
            val listenedMs = if (!o.has("listenedMs") || o.isNull("listenedMs")) 0L else
                PlayerExtrasValuePolicy.nonNegativeLong(o.opt("listenedMs"))
                    ?: throw IllegalArgumentException("Backup daily listening duration is invalid at index $i")
            DailyListeningEntity(day, listenedMs)
        }

        val app = context.applicationContext
        val room = AudoibooDatabase.get(app)
        room.withTransaction {
            room.libraryDao().replacePlayerExtras(
                history = history,
                bookmarks = bookmarks,
                daily = daily,
                totalMs = totalMs
            )
        }
        PlayerExtrasStore.refresh(app)
    }

    private fun requiredArray(root: JSONObject, key: String): JSONArray? {
        if (!root.has(key) || root.isNull(key)) return null
        return root.opt(key) as? JSONArray
            ?: throw IllegalArgumentException("Backup player extras $key is invalid")
    }

    /** Refresh Room-backed caches for callers that still use the reconciliation entry point. */
    suspend fun reconcile(context: Context) {
        PlayerExtrasStore.refresh(context.applicationContext)
    }
}
