package org.audoiboo.tracker

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.audoiboo.tracker.plugin.CatalogLibrarySourcePlugin
import org.audoiboo.tracker.plugin.DeviceWebViewResolutionRuntime
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.tts.SherpaAndroidAdapter
import org.audoiboo.tracker.tts.SherpaBackgroundRuntimeInstaller

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

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

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
        CatalogLibrarySourcePlugin.initialize(this)

        // Reinstall the process-local worker runtime after every process start. The concrete native
        // engine and model are still created lazily only when a persisted TTS job actually runs.
        SherpaBackgroundRuntimeInstaller.install(SherpaAndroidAdapter.factory())

        // Source discovery must see enabled package plugins from the first Activity frame.
        // initialize() is idempotent, so later callers remain safe and cheap.
        runCatching { PluginPackageRuntime.initialize(filesDir) }

        registerActivityLifecycleCallbacks(legacyLifecycle)
        ContinueListeningWidget.updateAll(this)

        appScope.launch {
            // Tracking series are the only existing user data that still needs legacy import support.
            runCatching { LegacyLibraryImporter.importIfNeeded(this@AudoibooApp) }
            // Old builds could replace stable catalog:// identity URLs with an audio-provider page.
            // Repair only rows whose catalog identity is provable from their stable entity IDs.
            runCatching { CatalogCanonicalUrlRepair.repair(this@AudoibooApp) }
            // Merge only strong provider duplicates into authoritative catalog book anchors.
            runCatching { CatalogBookDeduplicationRepair.repairAll(this@AudoibooApp) }
            // Source rows survive canonical dedupe/deletion by design. Drop only stale canonical ids;
            // keep the observations themselves so discovery/manual review can safely relink them.
            runCatching { LegacySourceMetadataRepair.repair(this@AudoibooApp) }
            // Rebind stale MediaStore/SAF URIs after reboot, provider changes or an app restore.
            runCatching { LibraryUriRecovery.recover(this@AudoibooApp) }
            runCatching { RoomCoverSync.enqueueAll(this@AudoibooApp) }
        }
    }
}
