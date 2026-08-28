package org.audoiboo.tracker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudoibooApp : Application() {
    private lateinit var playerExtrasPrefs: SharedPreferences
    private lateinit var playerQueuePrefs: SharedPreferences
    private lateinit var appSettingsPrefs: SharedPreferences
    private var trackerBridge: LegacyTrackerBridge? = null
    private var legacyConsumerCount = 0
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

    private fun isLegacyTrackerConsumer(activity: Activity): Boolean =
        activity is MainActivity || activity is PlayerActivity

    private val legacyLifecycle = object : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (!isLegacyTrackerConsumer(activity)) return
            legacyConsumerCount++
            if (legacyConsumerCount == 1) {
                trackerBridge = LegacyTrackerBridge(applicationContext).also { it.start() }
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (!isLegacyTrackerConsumer(activity)) return
            legacyConsumerCount = (legacyConsumerCount - 1).coerceAtLeast(0)
            if (legacyConsumerCount == 0) {
                trackerBridge?.stop()
                trackerBridge = null
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
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
        registerActivityLifecycleCallbacks(legacyLifecycle)
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
