package org.audoiboo.tracker.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import org.audoiboo.tracker.R
import org.audoiboo.tracker.ebook.BookDocument

/**
 * Persists long-running TTS execution intentions in WorkManager.
 *
 * The worker itself is provider-agnostic. The application installs the concrete runtime once the
 * native TTS adapter/model wiring is available; retries remain safe because TtsBookPlayerGenerator
 * resumes from the durable TtsSessionStore checkpoint.
 */
internal object TtsGenerationScheduler {
    private const val KEY_SESSION_ID = "tts_session_id"
    private const val KEY_TITLE = "tts_title"
    private fun workName(sessionId: String) = "audoiboo-tts-${safe(sessionId)}"

    /** Persists all process-death-safe inputs before handing the job to WorkManager. */
    fun enqueue(
        context: Context,
        session: TtsSession,
        document: BookDocument,
        model: VoiceModelSpec,
        outputDir: File,
    ) {
        require(session.providerId == "sherpa-onnx") { "Background TTS requires sherpa-onnx session" }
        require(session.voice.modelId == model.modelId) { "Background TTS model mismatch" }
        require(session.voice.modelVersion == model.version) { "Background TTS model version mismatch" }
        val root = File(context.filesDir, "tts")
        val sessionStore = TtsSessionStore(File(root, "sessions"))
        val persisted = sessionStore.load(session.sessionId)
        if (persisted == null) {
            sessionStore.save(session)
        } else {
            require(sameSessionIdentity(persisted, session)) { "Existing TTS checkpoint is incompatible" }
        }
        TtsBackgroundJobStore(File(root, "jobs")).save(
            TtsBackgroundBookJob(
                sessionId = session.sessionId,
                document = document,
                model = model,
                outputDir = outputDir.absolutePath,
            ),
        )
        enqueue(context, session.sessionId, document.title?.takeIf(String::isNotBlank) ?: "Аудіокнига")
    }

    fun enqueue(context: Context, sessionId: String, title: String) {
        require(sessionId.isNotBlank())
        val request = OneTimeWorkRequestBuilder<TtsGenerationWorker>()
            .setInputData(workDataOf(KEY_SESSION_ID to sessionId, KEY_TITLE to title))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            workName(sessionId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context, sessionId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(sessionId))
    }

    internal fun sessionId(worker: CoroutineWorker): String? = worker.inputData.getString(KEY_SESSION_ID)
    internal fun title(worker: CoroutineWorker): String = worker.inputData.getString(KEY_TITLE).orEmpty()

    private fun sameSessionIdentity(a: TtsSession, b: TtsSession): Boolean =
        a.sessionId == b.sessionId &&
            a.providerId == b.providerId &&
            a.documentFingerprint == b.documentFingerprint &&
            a.voice.stableKey == b.voice.stableKey &&
            a.speed == b.speed

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/** Concrete app runtime hook; keeps WorkManager orchestration independent from Sherpa JNI wiring. */
internal fun interface TtsBackgroundRuntime {
    suspend fun run(context: Context, sessionId: String): TtsSessionState
}

internal object TtsBackgroundRuntimeRegistry {
    @Volatile
    private var runtime: TtsBackgroundRuntime? = null

    fun install(value: TtsBackgroundRuntime) {
        runtime = value
    }

    fun clear() {
        runtime = null
    }

    fun current(): TtsBackgroundRuntime? = runtime
}

internal class TtsGenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val sessionId = TtsGenerationScheduler.sessionId(this) ?: return Result.failure()
        val title = TtsGenerationScheduler.title(this).ifBlank { "Аудіокнига" }
        setForeground(foregroundInfo(title))

        val runtime = TtsBackgroundRuntimeRegistry.current() ?: return Result.retry()
        return runCatching { runtime.run(applicationContext, sessionId) }
            .fold(
                onSuccess = { state ->
                    when (state) {
                        TtsSessionState.COMPLETED -> Result.success()
                        TtsSessionState.FAILED -> Result.failure()
                        else -> Result.retry()
                    }
                },
                onFailure = { Result.retry() },
            )
    }

    private fun foregroundInfo(title: String): ForegroundInfo {
        createChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_audoiboo)
            .setContentTitle("Озвучення книги")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= 35) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Озвучення книг", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val CHANNEL_ID = "audoiboo_tts"
        private const val NOTIFICATION_ID = 4113
    }
}
