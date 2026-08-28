package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object PlaybackStateRepository {
    private const val QUEUE_PREFS = "player_queue"
    private const val QUEUE_KEY = "book_dirs"
    private const val EXTRAS_PREFS = "player_extras"
    private const val SNAPSHOT_KEY = "playback_snapshot"

    data class Snapshot(
        val dir: String,
        val title: String,
        val uri: String,
        val fileIndex: Int,
        val positionMs: Long,
        val updatedAt: Long
    )

    fun observeQueue(context: Context): Flow<List<String>> =
        AudoibooDatabase.get(context).libraryDao().observePlaybackQueue().map { rows -> rows.map { it.dir } }

    suspend fun queue(context: Context): List<String> = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context).libraryDao().playbackQueue().map { it.dir }
    }

    suspend fun snapshot(context: Context): Snapshot? = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context).libraryDao().playbackResume()?.let {
            Snapshot(it.dir, it.title, it.uri, it.fileIndex, it.positionMs, it.updatedAt)
        }
    }

    suspend fun saveQueue(context: Context, dirs: List<String>) = withContext(Dispatchers.IO) {
        val clean = dirs.filter { it.isNotBlank() }.distinct()
        AudoibooDatabase.get(context).libraryDao().replacePlaybackQueue(clean)
        val a = JSONArray(); clean.forEach(a::put)
        context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE).edit().putString(QUEUE_KEY, a.toString()).apply()
    }

    suspend fun saveSnapshot(context: Context, value: Snapshot) = withContext(Dispatchers.IO) {
        if (value.dir.isBlank()) return@withContext
        AudoibooDatabase.get(context).libraryDao().upsertPlaybackResume(
            PlaybackResumeEntity(
                dir = value.dir,
                title = value.title,
                uri = value.uri,
                fileIndex = value.fileIndex.coerceAtLeast(0),
                positionMs = value.positionMs.coerceAtLeast(0L),
                updatedAt = value.updatedAt
            )
        )
        val q = JSONArray(); queue(context).forEach(q::put)
        val o = JSONObject()
            .put("dir", value.dir)
            .put("title", value.title)
            .put("uri", value.uri)
            .put("fileIndex", value.fileIndex.coerceAtLeast(0))
            .put("positionMs", value.positionMs.coerceAtLeast(0L))
            .put("queue", q)
            .put("updatedAt", value.updatedAt)
        context.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE).edit().putString(SNAPSHOT_KEY, o.toString()).apply()
    }

    suspend fun syncFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val queueRaw = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE).getString(QUEUE_KEY, "[]") ?: "[]"
        val queue = runCatching {
            val a = JSONArray(queueRaw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
        AudoibooDatabase.get(context).libraryDao().replacePlaybackQueue(queue.distinct())

        val raw = context.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE).getString(SNAPSHOT_KEY, null)
        val o = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
        val dir = o?.optString("dir").orEmpty()
        if (dir.isNotBlank()) {
            AudoibooDatabase.get(context).libraryDao().upsertPlaybackResume(
                PlaybackResumeEntity(
                    dir = dir,
                    title = o?.optString("title", dir).orEmpty(),
                    uri = o?.optString("uri").orEmpty(),
                    fileIndex = o?.optInt("fileIndex", 0) ?: 0,
                    positionMs = o?.optLong("positionMs", 0L) ?: 0L,
                    updatedAt = o?.optLong("updatedAt", System.currentTimeMillis()) ?: System.currentTimeMillis()
                )
            )
        }
    }
}
