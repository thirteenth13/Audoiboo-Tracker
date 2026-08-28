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
    private lateinit var appSettingsPrefs: SharedPreferences
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val playerExtrasListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ContinueListeningWidget.updateAll(this)
    }

    private val appSettingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key in setOf("wifi_only", "auto_find_archives", "dark_theme")) {
            appScope.launch { runCatching { PreferenceDataStore.syncFromLegacy(this@AudoibooApp) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DownloadScheduler.recover(this)
        WebDavSync.schedule(this)
        SeriesAutomationPrefs.schedule(this)
        CoverCache.enqueueTrackerCovers(this)

        playerExtrasPrefs = getSharedPreferences("player_extras", Context.MODE_PRIVATE)
        playerExtrasPrefs.registerOnSharedPreferenceChangeListener(playerExtrasListener)
        appSettingsPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        appSettingsPrefs.registerOnSharedPreferenceChangeListener(appSettingsListener)
        ContinueListeningWidget.updateAll(this)

        // Migration stays non-destructive while old consumers are being moved one by one.
        appScope.launch {
            runCatching { LegacyLibraryImporter.importIfNeeded(this@AudoibooApp) }
            runCatching { PreferenceDataStore.importLegacyIfNeeded(this@AudoibooApp) }
        }
    }
}
