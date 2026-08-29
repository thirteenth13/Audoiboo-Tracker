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
    private const val MIGRATION_PREFS = "room_migration"
    private const val MIGRATION_KEY = "playback_state_v1"

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
        parseSnapshot(resumeJson?.toString())?.let { dao.upsertPlaybackResume(it.toEntity()) }
        markMigrated(app)
    }

    /** One-time import from pre-Room builds. After this flag is set SharedPreferences is never authoritative again. */
    suspend fun reconcile(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val migration = app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (migration.getBoolean(MIGRATION_KEY, false)) return@withContext

        val dao = AudoibooDatabase.get(app).libraryDao()
        val queuePrefs = app.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        val extrasPrefs = app.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE)

        if (dao.playbackQueue().isEmpty() && queuePrefs.contains(QUEUE_KEY)) {
            dao.replacePlaybackQueue(parseQueue(queuePrefs.getString(QUEUE_KEY, "[]").orEmpty()))
        }
        if (dao.playbackResume() == null && extrasPrefs.contains(SNAPSHOT_KEY)) {
            parseSnapshot(extrasPrefs.getString(SNAPSHOT_KEY, null))?.let { dao.upsertPlaybackResume(it.toEntity()) }
        }
        markMigrated(app)
    }

    /** Explicit legacy import used only while restoring an old backup. */
    suspend fun syncFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val dao = AudoibooDatabase.get(app).libraryDao()
        val queuePrefs = app.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE)
        if (queuePrefs.contains(QUEUE_KEY)) {
            dao.replacePlaybackQueue(parseQueue(queuePrefs.getString(QUEUE_KEY, "[]").orEmpty()))
        }
        val extrasPrefs = app.getSharedPreferences(EXTRAS_PREFS, Context.MODE_PRIVATE)
        if (extrasPrefs.contains(SNAPSHOT_KEY)) {
            parseSnapshot(extrasPrefs.getString(SNAPSHOT_KEY, null))?.let { dao.upsertPlaybackResume(it.toEntity()) }
        }
        markMigrated(app)
    }

    private fun markMigrated(context: Context) {
        context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIGRATION_KEY, true).apply()
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
}
