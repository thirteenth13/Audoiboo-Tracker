# App-specific R8 rules.
# AndroidX/Room/Media3/WorkManager publish their own consumer rules.

# WorkManager persists worker class names and instantiates workers reflectively. Keep every
# app-owned ListenableWorker constructor instead of maintaining a fragile hand-written worker list.
-keep class org.audoiboo.tracker.** extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Components are also referenced from the manifest; keep their public names explicit
# so future manifest/component refactors remain safe under shrinking.
-keep public class org.audoiboo.tracker.AudoibooApp
-keep public class org.audoiboo.tracker.ManagedDownloadService
-keep public class org.audoiboo.tracker.PlaybackService
-keep public class org.audoiboo.tracker.SleepTimerService
-keep public class org.audoiboo.tracker.ContinueListeningWidget
-keep public class org.audoiboo.tracker.DownloadRecoveryReceiver
