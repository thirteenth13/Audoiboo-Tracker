package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Room-backed synchronous facade for the player's current resume snapshot. */
internal object PlaybackResumeStore {
    private const val LEGACY_PREFS = "player_extras"
    private const val LEGACY_KEY = "playback_snapshot"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<PlaybackStateRepository.Snapshot?>(null)
    @Volatile private var initialized = false

    fun observe(): StateFlow<PlaybackStateRepository.Snapshot?> = state.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            PlaybackStateRepository.reconcile(app)
            state.value = PlaybackStateRepository.snapshot(app)
        }
    }

    fun current(context: Context): PlaybackStateRepository.Snapshot? {
        initialize(context)
        return state.value ?: legacySnapshot(context.applicationContext)
    }

    fun save(context: Context, value: PlaybackStateRepository.Snapshot) {
        initialize(context)
        val clean = value.copy(
            fileIndex = value.fileIndex.coerceAtLeast(0),
            positionMs = value.positionMs.coerceAtLeast(0L)
        )
        state.value = clean
        scope.launch { PlaybackStateRepository.saveSnapshot(context.applicationContext, clean) }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        initialize(app)
        scope.launch { state.value = PlaybackStateRepository.snapshot(app) }
    }

    private fun legacySnapshot(context: Context): PlaybackStateRepository.Snapshot? = runCatching {
        val raw = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getString(LEGACY_KEY, null) ?: return null
        val o = JSONObject(raw)
        val dir = o.optString("dir").takeIf { it.isNotBlank() } ?: return null
        PlaybackStateRepository.Snapshot(
            dir = dir,
            title = o.optString("title", dir),
            uri = o.optString("uri"),
            fileIndex = o.optInt("fileIndex", 0).coerceAtLeast(0),
            positionMs = o.optLong("positionMs", 0L).coerceAtLeast(0L),
            updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
        )
    }.getOrNull()
}
