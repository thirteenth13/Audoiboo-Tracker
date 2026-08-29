package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Synchronous facade over DataStore for legacy call sites that need immediate settings reads. */
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
        initialize(context)
        return if (ready) state.value else PreferenceDataStore.legacySnapshot(context.applicationContext)
    }

    fun save(context: Context, value: ModernSettings) {
        initialize(context)
        val clean = value.copy(baseFolder = value.baseFolder.trim().trim('/').ifBlank { "Audoiboo" })
        state.value = clean
        scope.launch { PreferenceDataStore.save(context.applicationContext, clean) }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        initialize(app)
        scope.launch { state.value = PreferenceDataStore.current(app) }
    }
}
