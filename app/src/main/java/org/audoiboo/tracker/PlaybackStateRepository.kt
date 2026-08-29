package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal object PlaybackStateRepository {
    data class Snapshot(
        val dir: String,
        val title: String,
        val uri: String,
        val fileIndex: Int,
        val positionMs: Long,
        val updatedAt: Long
    )

    fun observeQueue(context: Context): Flow<List<String>> =
        AudoibooDatabase.get(context.applicationContext).libraryDao().observePlaybackQueue().map { rows -> rows.map { it.dir } }

    suspend fun queue(context: Context): List<String> = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context.applicationContext).libraryDao().playbackQueue().map { it.dir }
    }

    suspend fun snapshot(context: Context): Snapshot? = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context.applicationContext).libraryDao().playbackResume()?.let {
            Snapshot(it.dir, it.title, it.uri, it.fileIndex, it.positionMs, it.updatedAt)
        }
    }

    suspend fun saveQueue(context: Context, dirs: List<String>) = withContext(Dispatchers.IO) {
        val clean = dirs.filter { it.isNotBlank() }.distinct()
        AudoibooDatabase.get(context.applicationContext).libraryDao().replacePlaybackQueue(clean)
    }

    suspend fun saveSnapshot(context: Context, value: Snapshot) = withContext(Dispatchers.IO) {
        if (value.dir.isBlank()) return@withContext
        AudoibooDatabase.get(context.applicationContext).libraryDao().upsertPlaybackResume(value.toEntity())
    }

    suspend fun exportQueueJson(context: Context): JSONArray = withContext(Dispatchers.IO) {
        JSONArray().apply { queue(context).forEach(::put) }
    }

    suspend fun exportResumeJson(context: Context): JSONObject? = withContext(Dispatchers.IO) {
        snapshot(context)?.let { value ->
            JSONObject()
                .put("dir", value.dir)
                .put("title", value.title)
                .put("uri", value.uri)
                .put("fileIndex", value.fileIndex)
                .put("positionMs", value.positionMs)
                .put("updatedAt", value.updatedAt)
        }
    }

    suspend fun restoreRoomState(context: Context, queueJson: JSONArray?, resumeJson: JSONObject?) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = AudoibooDatabase.get(app).libraryDao()
        if (queueJson != null) {
            val dirs = (0 until queueJson.length()).mapNotNull { queueJson.optString(it).takeIf(String::isNotBlank) }.distinct()
            dao.replacePlaybackQueue(dirs)
        }
        parseSnapshot(resumeJson)?.let { dao.upsertPlaybackResume(it.toEntity()) }
        PlaybackResumeStore.refresh(app)
    }

    private fun parseSnapshot(o: JSONObject?): Snapshot? {
        if (o == null) return null
        val dir = o.optString("dir").takeIf(String::isNotBlank) ?: return null
        return Snapshot(
            dir = dir,
            title = o.optString("title", dir),
            uri = o.optString("uri"),
            fileIndex = o.optInt("fileIndex", 0).coerceAtLeast(0),
            positionMs = o.optLong("positionMs", 0L).coerceAtLeast(0L),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun Snapshot.toEntity() = PlaybackResumeEntity(
        dir = dir,
        title = title,
        uri = uri,
        fileIndex = fileIndex.coerceAtLeast(0),
        positionMs = positionMs.coerceAtLeast(0L),
        updatedAt = updatedAt
    )
}
