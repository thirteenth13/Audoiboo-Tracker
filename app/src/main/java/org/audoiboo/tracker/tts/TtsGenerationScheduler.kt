package org.audoiboo.tracker.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.audoiboo.tracker.R
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner

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
    internal fun workName(sessionId: String) = "audoiboo-tts-${TtsStableId.hex(sessionId)}"

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

    fun pause(context: Context, sessionId: String) {
        require(sessionId.isNotBlank())
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(sessionId))
        val store = sessionStore(context)
        store.load(sessionId)?.let { current ->
            if (current.state != TtsSessionState.COMPLETED) store.save(current.pause())
        }
    }

    fun resume(context: Context, sessionId: String, title: String) {
        require(sessionId.isNotBlank())
        val store = sessionStore(context)
        val current = requireNotNull(store.load(sessionId)) { "TTS session checkpoint is missing" }
        val job = requireNotNull(backgroundJobStore(context).load(sessionId)) {
            "TTS background job is missing or invalid"
        }
        validateResumeJob(current, job)
        val queued = current.queueForResume()
        store.save(queued)
        try {
            enqueue(context, sessionId, title)
        } catch (error: Throwable) {
            store.save(current)
            throw error
        }
    }

    fun cancel(context: Context, sessionId: String) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(sessionId))
    }

    internal fun sessionId(worker: CoroutineWorker): String? = worker.inputData.getString(KEY_SESSION_ID)
    internal fun title(worker: CoroutineWorker): String = worker.inputData.getString(KEY_TITLE).orEmpty()

    internal fun validateResumeJob(current: TtsSession, job: TtsBackgroundBookJob) {
        require(current.providerId == "sherpa-onnx") { "Background TTS requires sherpa-onnx session" }
        require(job.sessionId == current.sessionId) { "TTS background job session mismatch" }
        require(job.model.modelId == current.voice.modelId) { "TTS background job model mismatch" }
        require(job.model.version == current.voice.modelVersion) { "TTS background job model version mismatch" }
        require(job.model.language.equals(current.voice.language, ignoreCase = true)) {
            "TTS background job language mismatch"
        }
        require(TtsSynthesisPlanner.fingerprint(job.document) == current.documentFingerprint) {
            "TTS background job document mismatch"
        }
        require(File(job.outputDir).isAbsolute) { "TTS background output directory must be absolute" }
    }

    private fun sessionStore(context: Context): TtsSessionStore =
        TtsSessionStore(File(context.applicationContext.filesDir, "tts/sessions"))

    private fun backgroundJobStore(context: Context): TtsBackgroundJobStore =
        TtsBackgroundJobStore(File(context.applicationContext.filesDir, "tts/jobs"))

    private fun sameSessionIdentity(a: TtsSession, b: TtsSession): Boolean =
        a.sessionId == b.sessionId &&
            a.providerId == b.providerId &&
            a.documentFingerprint == b.documentFingerprint &&
            a.voice.stableKey == b.voice.stableKey &&
            a.speed == b.speed
}

/** Handles the foreground notification pause action without exposing the receiver outside the app. */
class TtsPauseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)?.takeIf(String::isNotBlank) ?: return
        TtsGenerationScheduler.pause(context.applicationContext, sessionId)
    }

    companion object {
        private const val ACTION_PAUSE = "org.audoiboo.tracker.tts.PAUSE"
        private const val EXTRA_SESSION_ID = "session_id"

        internal fun pendingIntent(context: Context, sessionId: String): PendingIntent {
            val intent = Intent(context, TtsPauseReceiver::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            return PendingIntent.getBroadcast(
                context,
                TtsStableId.notificationId(sessionId),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
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
    override suspend fun doWork(): Result = coroutineScope {
        val sessionId = TtsGenerationScheduler.sessionId(this@TtsGenerationWorker)
            ?: return@coroutineScope Result.failure()
        val title = TtsGenerationScheduler.title(this@TtsGenerationWorker).ifBlank { "Аудіокнига" }
        val filesRoot = File(applicationContext.filesDir, "tts")
        val sessionStore = TtsSessionStore(File(filesRoot, "sessions"))
        val totalChunks = TtsBackgroundJobStore(File(filesRoot, "jobs"))
            .load(sessionId)
            ?.chunkCount
            ?.coerceAtLeast(0)
            ?: 0

        createChannel()
        setForeground(foregroundInfo(sessionId, title, sessionStore.load(sessionId)?.nextGlobalChunkIndex ?: 0, totalChunks))

        val runtime = TtsBackgroundRuntimeRegistry.current() ?: return@coroutineScope Result.retry()
        val progressJob = launch {
            while (isActive) {
                delay(PROGRESS_REFRESH_MS)
                val current = sessionStore.load(sessionId)?.nextGlobalChunkIndex ?: 0
                setForeground(foregroundInfo(sessionId, title, current, totalChunks))
            }
        }

        try {
            when (runtime.run(applicationContext, sessionId)) {
                TtsSessionState.COMPLETED -> Result.success()
                TtsSessionState.FAILED -> Result.failure()
                else -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            Result.retry()
        } finally {
            progressJob.cancelAndJoin()
        }
    }

    private fun foregroundInfo(sessionId: String, title: String, currentChunk: Int, totalChunks: Int): ForegroundInfo {
        val boundedCurrent = currentChunk.coerceAtLeast(0).coerceAtMost(totalChunks.coerceAtLeast(0))
        val hasProgress = totalChunks > 0
        val content = if (hasProgress) "$title • $boundedCurrent/$totalChunks" else title
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_audoiboo)
            .setContentTitle("Озвучення книги")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(totalChunks.coerceAtLeast(0), boundedCurrent, !hasProgress)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Пауза",
                TtsPauseReceiver.pendingIntent(applicationContext, sessionId),
            )
            .build()
        val notificationId = TtsStableId.notificationId(sessionId)
        return if (Build.VERSION.SDK_INT >= 35) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            ForegroundInfo(notificationId, notification)
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
        private const val PROGRESS_REFRESH_MS = 1_000L
    }
}
