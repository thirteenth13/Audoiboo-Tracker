package org.audoiboo.tracker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.audoiboo.tracker.plugin.DeviceWebViewResolutionRuntime
import org.audoiboo.tracker.plugin.PluginPackageRuntime

class AudoibooApp : Application() {
    private var trackerBridge: LegacyTrackerBridge? = null
    private var legacyConsumerCount = 0
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        AppSettingsStore.initialize(this)
        ManagedDownloads.initialize(this)
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
        DeviceWebViewResolutionRuntime.initialize(this)

        // Source discovery must see enabled package plugins from the first Activity frame.
        // initialize() is idempotent, so later callers remain safe and cheap.
        runCatching { PluginPackageRuntime.initialize(filesDir) }

        registerActivityLifecycleCallbacks(legacyLifecycle)
        ContinueListeningWidget.updateAll(this)

        appScope.launch {
            // Tracking series are the only existing user data that still needs legacy import support.
            runCatching { LegacyLibraryImporter.importIfNeeded(this@AudoibooApp) }
            // Rebind stale MediaStore/SAF URIs after reboot, provider changes or an app restore.
            runCatching { LibraryUriRecovery.recover(this@AudoibooApp) }
            runCatching { RoomCoverSync.enqueueAll(this@AudoibooApp) }
        }
    }
}
