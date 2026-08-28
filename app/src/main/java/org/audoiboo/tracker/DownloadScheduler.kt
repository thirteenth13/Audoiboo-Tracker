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
 * WorkManager persists and schedules download intentions. The actual long-running
 * transfer still happens inside ManagedDownloadService as a foreground service.
 */
internal object DownloadScheduler {
    private const val KEY_ID = "download_id"
    private fun workName(id: String) = "audoiboo-download-$id"

    fun enqueue(context: Context, id: String, delayedRetry: Boolean = false) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (AppPrefs.wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val builder = OneTimeWorkRequestBuilder<DownloadKickWorker>()
            .setInputData(workDataOf(KEY_ID to id))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (delayedRetry) builder.setInitialDelay(30, TimeUnit.SECONDS)
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.REPLACE,
            builder.build()
        )
    }

    fun cancel(context: Context, id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    fun recover(context: Context) {
        ManagedDownloads.list(context)
            .filter { it.state in setOf(ManagedDownloadState.QUEUED, ManagedDownloadState.DOWNLOADING, ManagedDownloadState.EXTRACTING) }
            .forEach { enqueue(context, it.id) }
    }

    internal fun id(input: androidx.work.Data): String? = input.getString(KEY_ID)
}

internal class DownloadKickWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val id = DownloadScheduler.id(inputData) ?: return Result.failure()
        val record = ManagedDownloads.get(applicationContext, id) ?: return Result.success()
        if (record.state in setOf(ManagedDownloadState.COMPLETED, ManagedDownloadState.CANCELLED, ManagedDownloadState.PAUSED)) {
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
