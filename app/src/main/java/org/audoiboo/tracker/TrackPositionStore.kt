package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Room-backed track-position store used by player progress and resume logic. */
object TrackPositionStore {
    private const val LEGACY_PREFS = "player_positions"
    private const val MIGRATION_PREFS = "room_migrations"
    private const val MIGRATION_KEY = "track_positions_v5"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val pending = mutableMapOf<String, Long>()
    @Volatile private var initialized = false

    fun observe(): StateFlow<Map<String, Long>> = positions.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            val dao = AudoibooDatabase.get(app).libraryDao()
            migrateLegacyIfNeeded(app, dao)
            val room = dao.trackPositions().associate { it.uri to it.positionMs.coerceAtLeast(0L) }
            val pendingSnapshot = synchronized(pending) { pending.toMap() }
            positions.value = room + pendingSnapshot
            if (pendingSnapshot.isNotEmpty()) {
                dao.upsertTrackPositions(pendingSnapshot.map { (uri, value) -> TrackPositionEntity(uri, value.coerceAtLeast(0L)) })
                synchronized(pending) {
                    pendingSnapshot.forEach { (uri, value) -> if (pending[uri] == value) pending.remove(uri) }
                }
            }
        }
    }

    fun position(context: Context, uri: Uri): Long {
        initialize(context)
        val key = uri.toString()
        return positions.value[key]
            ?: synchronized(pending) { pending[key] }
            ?: context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
    }

    fun save(context: Context, uri: Uri, positionMs: Long) {
        initialize(context)
        val key = uri.toString()
        val value = positionMs.coerceAtLeast(0L)
        positions.value = positions.value + (key to value)
        synchronized(pending) { pending[key] = value }
        val app = context.applicationContext
        scope.launch {
            AudoibooDatabase.get(app).libraryDao().upsertTrackPosition(TrackPositionEntity(key, value))
            synchronized(pending) { if (pending[key] == value) pending.remove(key) }
        }
    }

    suspend fun exportJson(context: Context): JSONObject {
        val out = JSONObject()
        AudoibooDatabase.get(context.applicationContext).libraryDao().trackPositions().forEach { row ->
            out.put(row.uri, row.positionMs.coerceAtLeast(0L))
        }
        return out
    }

    suspend fun restoreJson(context: Context, json: JSONObject?) {
        if (json == null) return
        val rows = buildList {
            val keys = json.keys()
            while (keys.hasNext()) {
                val uri = keys.next()
                if (uri.isBlank()) continue
                add(TrackPositionEntity(uri, json.optLong(uri, 0L).coerceAtLeast(0L)))
            }
        }
        val app = context.applicationContext
        val db = AudoibooDatabase.get(app)
        val dao = db.libraryDao()
        // Backup restore is an explicit replacement of this isolated table.
        db.openHelper.writableDatabase.execSQL("DELETE FROM track_positions")
        if (rows.isNotEmpty()) dao.upsertTrackPositions(rows)
        synchronized(pending) { pending.clear() }
        positions.value = rows.associate { it.uri to it.positionMs }
        app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        app.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE).edit().putBoolean(MIGRATION_KEY, true).apply()
        initialized = true
    }

    private suspend fun migrateLegacyIfNeeded(context: Context, dao: LibraryDao) {
        val flags = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (flags.getBoolean(MIGRATION_KEY, false)) return
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val rows = legacy.all.mapNotNull { (uri, raw) ->
            val value = raw as? Long ?: return@mapNotNull null
            if (uri.isBlank()) null else TrackPositionEntity(uri, value.coerceAtLeast(0L))
        }
        if (rows.isNotEmpty()) dao.upsertTrackPositions(rows)
        flags.edit().putBoolean(MIGRATION_KEY, true).commit()
        legacy.edit().clear().apply()
    }
}
