package org.audoiboo.tracker

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Room-backed track-position store with a temporary compatibility mirror for
 * the old synchronous PlayerPrefs API. Room remains durable storage while the
 * legacy preference file is kept in sync until the last caller is removed.
 */
object TrackPositionStore {
    private const val LEGACY_PREFS = "player_positions"
    private const val MIGRATION_PREFS = "room_migrations"
    private const val MIGRATION_KEY = "track_positions_v5"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val pending = mutableMapOf<String, Long>()
    @Volatile private var initialized = false
    private var legacyPrefs: SharedPreferences? = null
    private var legacyListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun observe(): StateFlow<Map<String, Long>> = positions.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        legacyPrefs = prefs
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key.isNullOrBlank()) return@OnSharedPreferenceChangeListener
            val value = shared.getLong(key, 0L).coerceAtLeast(0L)
            positions.value = positions.value + (key to value)
            synchronized(pending) { pending[key] = value }
            scope.launch {
                AudoibooDatabase.get(app).libraryDao().upsertTrackPosition(TrackPositionEntity(key, value))
                synchronized(pending) { if (pending[key] == value) pending.remove(key) }
            }
        }
        legacyListener = listener
        prefs.registerOnSharedPreferenceChangeListener(listener)

        scope.launch {
            val dao = AudoibooDatabase.get(app).libraryDao()
            migrateLegacyIfNeeded(app, dao)
            val room = dao.trackPositions().associate { it.uri to it.positionMs.coerceAtLeast(0L) }
            val pendingSnapshot = synchronized(pending) { pending.toMap() }
            val merged = room + pendingSnapshot
            positions.value = merged
            // Compatibility mirror: populate missing/older values so current
            // PlayerPrefs reads the Room-backed state until it is removed.
            val edit = prefs.edit()
            var changed = false
            merged.forEach { (uri, value) ->
                if (!prefs.contains(uri) || prefs.getLong(uri, -1L) != value) {
                    edit.putLong(uri, value)
                    changed = true
                }
            }
            if (changed) edit.apply()
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
        // Temporary compatibility write. The listener also persists this to Room.
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().putLong(key, value).apply()
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
        // Do not clear the compatibility prefs yet: current PlayerPrefs still
        // reads them synchronously. They can be removed after that final caller
        // is switched to TrackPositionStore.
        flags.edit().putBoolean(MIGRATION_KEY, true).commit()
    }
}
