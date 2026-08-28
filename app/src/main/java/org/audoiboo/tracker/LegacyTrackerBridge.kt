package org.audoiboo.tracker

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Temporary bridge while MainActivity is being moved from its legacy JSON model to Room Flow. */
internal class LegacyTrackerBridge(private val context: Context) {
    private val prefs = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
    }

    fun stop() = prefs.unregisterOnSharedPreferenceChangeListener(listener)
}
