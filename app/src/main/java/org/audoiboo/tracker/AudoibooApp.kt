package org.audoiboo.tracker

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudoibooApp : Application() {
    private lateinit var playerExtrasPrefs: SharedPreferences
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playerExtrasListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ContinueListeningWidget.updateAll(this)
    }

    override fun onCreate() {
        super.onCreate()
        DownloadScheduler.recover(this)
        WebDavSync.schedule(this)
        SeriesAutomationPrefs.schedule(this)
        CoverCache.enqueueTrackerCovers(this)
        playerExtrasPrefs = getSharedPreferences("player_extras", Context.MODE_PRIVATE)
        playerExtrasPrefs.registerOnSharedPreferenceChangeListener(playerExtrasListener)
        ContinueListeningWidget.updateAll(this)

        // Phase 1 migration is intentionally non-destructive: old preferences remain the live source
        // while Room/DataStore are populated and validated by CI and real-device upgrades.
        appScope.launch {
            runCatching { LegacyLibraryImporter.importIfNeeded(this@AudoibooApp) }
            runCatching { PreferenceDataStore.importLegacyIfNeeded(this@AudoibooApp) }
        }
    }
}
