package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Synchronous facade over DataStore for call sites that need immediate settings reads. */
internal object AppSettingsStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(ModernSettings())
    @Volatile private var initialized = false
    @Volatile private var ready = false

    fun observe(): StateFlow<ModernSettings> = state.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            PreferenceDataStore.reconcile(app)
            state.value = PreferenceDataStore.current(app)
            ready = true
            PreferenceDataStore.observe(app).collect { state.value = it }
        }
    }

    fun current(context: Context): ModernSettings {
        val app = context.applicationContext
        initialize(app)
        if (ready) return state.value

        // DataStore is authoritative. Block only the first cold read so callers never observe
        // defaults merely because the asynchronous warm-up has not completed yet.
        return synchronized(this) {
            if (ready) state.value
            else runBlocking(Dispatchers.IO) {
                PreferenceDataStore.reconcile(app)
                PreferenceDataStore.current(app).also {
                    state.value = it
                    ready = true
                }
            }
        }
    }

    fun save(context: Context, value: ModernSettings) {
        initialize(context)
        val clean = value.copy(baseFolder = value.baseFolder.trim().trim('/').ifBlank { "Audoiboo" })
        state.value = clean
        ready = true
        scope.launch { PreferenceDataStore.save(context.applicationContext, clean) }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        initialize(app)
        scope.launch {
            state.value = PreferenceDataStore.current(app)
            ready = true
        }
    }
}
