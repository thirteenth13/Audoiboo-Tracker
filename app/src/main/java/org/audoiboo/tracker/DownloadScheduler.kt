package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * WorkManager persists download intentions. The actual transfer still happens in
 * ManagedDownloadService. Normal kicks and delayed retries use separate unique work names so a
 * retry cannot be lost because the worker that started the failed transfer is still finishing.
 */
internal object DownloadScheduler {
    private const val KEY_ID = "download_id"
    private const val KEY_RETRY = "download_retry"
    private const val RECOVERY_WORK = "audoiboo-download-recovery"
    private fun workName(id: String) = "audoiboo-download-$id"
    private fun retryWorkName(id: String) = "audoiboo-download-retry-$id"

    private fun constraints(context: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (AppPrefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    fun enqueue(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<DownloadKickWorker>()
            .setInputData(workDataOf(KEY_ID to id, KEY_RETRY to false))
            .setConstraints(constraints(context))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Compatibility route for the service while keeping retry work independently cancellable. */
    fun enqueue(context: Context, id: String, delayedRetry: Boolean) {
        if (delayedRetry) enqueueRetry(context, id) else enqueue(context, id)
    }

    fun enqueueRetry(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<DownloadKickWorker>()
            .setInputData(workDataOf(KEY_ID to id, KEY_RETRY to true))
            .setConstraints(constraints(context))
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Retry workers never write the staging file themselves. REPLACE guarantees that a retry
        // scheduled by a fresh failure is not dropped behind an older retry worker still exiting.
        WorkManager.getInstance(context).enqueueUniqueWork(
            retryWorkName(id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context, id: String) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(workName(id))
        work.cancelUniqueWork(retryWorkName(id))
    }

    fun scheduleRecovery(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RECOVERY_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadRecoveryWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
        )
    }

    fun recover(context: Context) {
        ManagedDownloads.list(context)
            .filter { DownloadRecoveryPolicy.shouldRecover(it.state) }
            .forEach { record ->
                val normalized = DownloadRecoveryPolicy.normalizedState(record.state)
                if (normalized != record.state) {
                    ManagedDownloads.saveOne(context, record.copy(state = normalized, error = null))
                }
                enqueue(context, record.id)
            }
    }

    internal fun id(input: androidx.work.Data): String? = input.getString(KEY_ID)
    internal fun isRetry(input: androidx.work.Data): Boolean = input.getBoolean(KEY_RETRY, false)
}

internal class DownloadRecoveryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        ManagedDownloads.initialize(applicationContext)
        DownloadScheduler.recover(applicationContext)
        Result.success()
    }.getOrElse { Result.retry() }
}

internal class DownloadKickWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = DownloadScheduler.id(inputData) ?: return Result.failure()
        var record = ManagedDownloads.get(applicationContext, id) ?: return Result.success()
        if (DownloadScheduler.isRetry(inputData)) {
            // A delayed retry is valid only while the exact transfer is still FAILED. Manual
            // resume/cancel/pause changes the state, making this stale retry a harmless no-op.
            if (record.state != ManagedDownloadState.FAILED) return Result.success()
            record = record.copy(state = ManagedDownloadState.QUEUED)
            ManagedDownloads.saveOne(applicationContext, record)
        } else if (!DownloadRecoveryPolicy.workerCanKick(record.state)) {
            return Result.success()
        }
        return runCatching {
            val intent = Intent(applicationContext, ManagedDownloadService::class.java)
                .setAction(ManagedDownloadService.ACTION_START)
                .putExtra(ManagedDownloadService.EXTRA_ID, id)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) applicationContext.startForegroundService(intent)
            else applicationContext.startService(intent)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
