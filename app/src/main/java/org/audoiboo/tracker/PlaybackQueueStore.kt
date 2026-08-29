package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Room-native live queue facade for synchronous player actions. */
internal object PlaybackQueueStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<List<String>>(emptyList())
    @Volatile private var initialized = false

    fun observe(): StateFlow<List<String>> = state.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            PlaybackStateRepository.reconcile(app)
            PlaybackStateRepository.observeQueue(app).collect { roomQueue ->
                state.value = roomQueue.distinct()
            }
        }
    }

    fun current(context: Context): List<String> {
        initialize(context)
        return state.value
    }

    fun save(context: Context, dirs: List<String>) {
        initialize(context)
        val clean = dirs.filter { it.isNotBlank() }.distinct()
        state.value = clean
        scope.launch { PlaybackStateRepository.saveQueue(context.applicationContext, clean) }
    }
}
