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

/**
 * Fast in-memory facade over Room track_positions.
 *
 * Player UI remains synchronous while Room writes happen off the main thread.
 * Existing SharedPreferences positions are imported once, then removed only
 * after Room accepted the migrated rows.
 */
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
            val merged = synchronized(pending) { room + pending }
            positions.value = merged
            synchronized(pending) {
                if (pending.isNotEmpty()) {
                    dao.upsertTrackPositions(pending.map { (uri, value) -> TrackPositionEntity(uri, value.coerceAtLeast(0L)) })
                    pending.clear()
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
