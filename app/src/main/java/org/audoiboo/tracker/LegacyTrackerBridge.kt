package org.audoiboo.tracker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Temporary two-way bridge while tracker screens are moved from legacy JSON to Room Flow. */
internal class LegacyTrackerBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var roomJob: Job? = null
    @Volatile private var lastSeen: String? = null

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key != "library") return@OnSharedPreferenceChangeListener
        val raw = p.getString("library", "[]") ?: "[]"
        if (raw == lastSeen) return@OnSharedPreferenceChangeListener
        lastSeen = raw
        scope.launch { runCatching { LibraryRepository.restoreLegacyJson(context, raw) } }
    }

    fun start() {
        lastSeen = prefs.getString("library", "[]") ?: "[]"
        prefs.registerOnSharedPreferenceChangeListener(listener)
        roomJob?.cancel()
        roomJob = scope.launch {
            LibraryRepository.observe(context).collectLatest { library ->
                runCatching {
                    val raw = LibraryRepository.mirrorLegacy(context, library)
                    lastSeen = raw
                }
            }
        }
    }

    fun stop() {
        roomJob?.cancel()
        roomJob = null
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
