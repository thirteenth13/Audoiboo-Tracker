package org.audoiboo.tracker

import android.app.Activity
import android.app.Application
import android.content.SharedPreferences
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AudoibooApp : Application() {
    private lateinit var appSettingsPrefs: SharedPreferences
    private var trackerBridge: LegacyTrackerBridge? = null
    private var legacyConsumerCount = 0
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appSettingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        appScope.launch { runCatching { PreferenceDataStore.syncFromLegacy(this@AudoibooApp) } }
    }

    private fun isLegacyTrackerConsumer(activity: Activity): Boolean = activity is MainActivity

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
        RoomTrackerCatalog.start(this)
        TrackPositionStore.initialize(this)
        PlaybackQueueStore.initialize(this)
        PlaybackResumeStore.initialize(this)
        PlayerExtrasStore.initialize(this)
        PlayerTagStore.initialize(this)
        PlayerStateStore.initialize(this)

        appSettingsPrefs = getSharedPreferences("app_settings", MODE_PRIVATE)
        appSettingsPrefs.registerOnSharedPreferenceChangeListener(appSettingsListener)
        registerActivityLifecycleCallbacks(legacyLifecycle)
        ContinueListeningWidget.updateAll(this)

        appScope.launch {
            runCatching { LegacyLibraryImporter.importIfNeeded(this@AudoibooApp) }
            runCatching { PreferenceDataStore.reconcile(this@AudoibooApp) }
            runCatching { PlaybackStateRepository.reconcile(this@AudoibooApp) }
            runCatching { RoomTagSync.syncFromLegacy(this@AudoibooApp) }
            runCatching { PlayerExtrasRoomSync.syncFromLegacy(this@AudoibooApp) }
            runCatching { RoomCoverSync.enqueueAll(this@AudoibooApp) }
        }
    }
}
