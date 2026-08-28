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
    private lateinit var playerQueuePrefs: SharedPreferences
    private lateinit var appSettingsPrefs: SharedPreferences
    private lateinit var trackerBridge: LegacyTrackerBridge
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val playerExtrasListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        ContinueListeningWidget.updateAll(this)
        if (key == null || key == "playback_snapshot") {
            appScope.launch { runCatching { PlaybackStateRepository.syncFromLegacy(this@AudoibooApp) } }
        }
        if (key == null || key == "book_tags") {
            appScope.launch { runCatching { RoomTagSync.syncFromLegacy(this@AudoibooApp) } }
        }
    }
    private val playerQueueListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "book_dirs") {
            appScope.launch { runCatching { PlaybackStateRepository.syncFromLegacy(this@AudoibooApp) } }
        }
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

        playerExtrasPrefs = getSharedPreferences("player_extras", Context.MODE_PRIVATE)
        playerExtrasPrefs.registerOnSharedPreferenceChangeListener(playerExtrasListener)
        playerQueuePrefs = getSharedPreferences("player_queue", Context.MODE_PRIVATE)
        playerQueuePrefs.registerOnSharedPreferenceChangeListener(playerQueueListener)
        appSettingsPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        appSettingsPrefs.registerOnSharedPreferenceChangeListener(appSettingsListener)
        trackerBridge = LegacyTrackerBridge(applicationContext).also { it.start() }
        ContinueListeningWidget.updateAll(this)

        appScope.launch {
            runCatching { LegacyLibraryImporter.importIfNeeded(this@AudoibooApp) }
            runCatching { PreferenceDataStore.reconcile(this@AudoibooApp) }
            runCatching { PlaybackStateRepository.reconcile(this@AudoibooApp) }
            runCatching { RoomTagSync.syncFromLegacy(this@AudoibooApp) }
            runCatching { RoomCoverSync.enqueueAll(this@AudoibooApp) }
        }
    }
}
