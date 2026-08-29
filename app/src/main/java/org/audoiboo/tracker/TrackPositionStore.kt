package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
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
            val room = dao.trackPositions().associate { it.uri to it.positionMs.coerceAtLeast(0L) }
            val pendingSnapshot = synchronized(pending) { pending.toMap() }
            positions.value = TrackPositionSnapshotPolicy.merge(room, positions.value, pendingSnapshot)
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
        return positions.value[key] ?: synchronized(pending) { pending[key] } ?: 0L
    }

    fun save(context: Context, uri: Uri, positionMs: Long) {
        initialize(context)
        val key = uri.toString()
        if (!TrackPositionPolicy.validKey(key)) return
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
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        val room = dao.trackPositions().associate { it.uri to it.positionMs }
        val cached = positions.value
        val pendingSnapshot = synchronized(pending) { pending.toMap() }
        val snapshot = TrackPositionSnapshotPolicy.merge(room, cached, pendingSnapshot)
        return JSONObject().apply { snapshot.forEach { (uri, value) -> put(uri, value) } }
    }

    suspend fun restoreJson(context: Context, json: JSONObject?) {
        if (json == null) return
        val rows = buildList {
            val keys = json.keys()
            while (keys.hasNext()) {
                val uri = keys.next()
                require(TrackPositionPolicy.validKey(uri)) { "Backup track position URI is invalid" }
                val value = TrackPositionPolicy.normalize(json.opt(uri))
                    ?: throw IllegalArgumentException("Backup track position is invalid for $uri")
                add(TrackPositionEntity(uri, value))
            }
        }
        val app = context.applicationContext
        val db = AudoibooDatabase.get(app)
        val dao = db.libraryDao()
        db.withTransaction {
            db.openHelper.writableDatabase.execSQL("DELETE FROM track_positions")
            if (rows.isNotEmpty()) dao.upsertTrackPositions(rows)
        }
        synchronized(pending) { pending.clear() }
        positions.value = rows.associate { it.uri to it.positionMs }
        initialized = true
    }
}
