package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Room-backed synchronous facade for the player's current resume snapshot. */
internal object PlaybackResumeStore {
    private const val WIDGET_REFRESH_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<PlaybackStateRepository.Snapshot?>(null)
    @Volatile private var initialized = false
    @Volatile private var lastWidgetRefresh = 0L

    fun observe(): StateFlow<PlaybackStateRepository.Snapshot?> = state.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch { state.value = PlaybackStateRepository.snapshot(app) }
    }

    fun current(context: Context): PlaybackStateRepository.Snapshot? {
        initialize(context)
        return state.value
    }

    fun rememberBook(context: Context, dir: String, title: String, uri: String) {
        if (dir.isBlank()) return
        initialize(context)
        val current = state.value
        if (current?.dir == dir && (uri.isBlank() || current.uri == uri)) return
        save(
            context,
            PlaybackStateRepository.Snapshot(
                dir = dir,
                title = title.ifBlank { dir },
                uri = uri,
                fileIndex = 0,
                positionMs = 0L,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun save(context: Context, value: PlaybackStateRepository.Snapshot) {
        initialize(context)
        val app = context.applicationContext
        val previous = state.value
        val clean = value.copy(
            fileIndex = value.fileIndex.coerceAtLeast(0),
            positionMs = value.positionMs.coerceAtLeast(0L)
        )
        state.value = clean
        val now = System.currentTimeMillis()
        val refreshWidget = previous?.dir != clean.dir || previous.uri != clean.uri || now - lastWidgetRefresh >= WIDGET_REFRESH_MS
        if (refreshWidget) lastWidgetRefresh = now
        scope.launch {
            PlaybackStateRepository.saveSnapshot(app, clean)
            if (refreshWidget) ContinueListeningWidget.updateAll(app)
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        initialize(app)
        scope.launch {
            state.value = PlaybackStateRepository.snapshot(app)
            ContinueListeningWidget.updateAll(app)
        }
    }
}
