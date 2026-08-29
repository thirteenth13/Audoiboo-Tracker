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
        // Compatibility mirror until PlayerActivity's final local queue facade is removed.
        writeLegacyQueue(context, clean)
    }

    suspend fun saveSnapshot(context: Context, value: Snapshot) = withContext(Dispatchers.IO) {
        if (value.dir.isBlank()) return@withContext
        AudoibooDatabase.get(context).libraryDao().upsertPlaybackResume(value.toEntity())
        writeLegacySnapshot(context, value, queue(context))
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
        val dao = AudoibooDatabase.get(context).libraryDao()
        if (queueJson != null) {
            val dirs = (0 until queueJson.length()).mapNotNull { queueJson.optString(it).takeIf(String::isNotBlank) }.distinct()
            dao.replacePlaybackQueue(dirs)
            writeLegacyQueue(context, dirs)
        }
        parseSnapshot(resumeJson?.toString())?.let {
            dao.upsertPlaybackResume(it.toEntity())
            writeLegacySnapshot(context, it, dao.playbackQueue().map { row -> row.dir })
        }
    }

    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val queuePrefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val extrasPrefs = context.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE)

        val resolvedQueue = if (queuePrefs.contains(QUEUE_KEY)) {
            parseQueue(queuePrefs.getString(QUEUE_KEY, "[]").orEmpty()).also { dao.replacePlaybackQueue(it) }
        } else {
            dao.playbackQueue().map { it.dir }.also { if (it.isNotEmpty()) writeLegacyQueue(context, it) }
        }

        if (extrasPrefs.contains(SNAPSHOT_KEY)) {
            parseSnapshot(extrasPrefs.getString(SNAPSHOT_KEY, null))?.let { dao.upsertPlaybackResume(it.toEntity()) }
        } else {
            dao.playbackResume()?.let {
                writeLegacySnapshot(context, Snapshot(it.dir, it.title, it.uri, it.fileIndex, it.positionMs, it.updatedAt), resolvedQueue)
            }
        }
    }

    suspend fun syncFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context).libraryDao()
        val queuePrefs = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        if (queuePrefs.contains(QUEUE_KEY)) {
            dao.replacePlaybackQueue(parseQueue(queuePrefs.getString(QUEUE_KEY, "[]").orEmpty()))
        }
        val extrasPrefs = context.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE)
        if (extrasPrefs.contains(SNAPSHOT_KEY)) {
            parseSnapshot(extrasPrefs.getString(SNAPSHOT_KEY, null))?.let { dao.upsertPlaybackResume(it.toEntity()) }
        }
    }

    private fun parseQueue(raw: String): List<String> = runCatching {
        val a = JSONArray(raw.ifBlank { "[]" })
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.distinct()
    }.getOrDefault(emptyList())

    private fun parseSnapshot(raw: String?): Snapshot? = raw?.let { text ->
        runCatching {
            val o = JSONObject(text)
            val dir = o.optString("dir").takeIf { it.isNotBlank() } ?: return@runCatching null
            Snapshot(
                dir = dir,
                title = o.optString("title", dir),
                uri = o.optString("uri"),
                fileIndex = o.optInt("fileIndex", 0).coerceAtLeast(0),
                positionMs = o.optLong("positionMs", 0L).coerceAtLeast(0L),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    private fun Snapshot.toEntity() = PlaybackResumeEntity(
        dir = dir,
        title = title,
        uri = uri,
        fileIndex = fileIndex.coerceAtLeast(0),
        positionMs = positionMs.coerceAtLeast(0L),
        updatedAt = updatedAt
    )

    private fun writeLegacyQueue(context: Context, dirs: List<String>) {
        val a = JSONArray(); dirs.filter { it.isNotBlank() }.distinct().forEach(a::put)
        context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE).edit().putString(QUEUE_KEY, a.toString()).apply()
    }

    private fun writeLegacySnapshot(context: Context, value: Snapshot, dirs: List<String>) {
        val q = JSONArray(); dirs.filter { it.isNotBlank() }.distinct().forEach(q::put)
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
}
