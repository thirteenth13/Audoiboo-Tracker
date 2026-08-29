package org.audoiboo.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

internal object SeriesAutomationPrefs {
    private const val WORK = "audoiboo-series-watch"

    fun enabled(context: Context) = AppSettingsStore.current(context).seriesAutomationEnabled
    fun autoDownload(context: Context) = AppSettingsStore.current(context).seriesAutoDownload
    fun wifiOnly(context: Context) = AppSettingsStore.current(context).seriesWifiOnly

    fun save(context: Context, enabled: Boolean, autoDownload: Boolean, wifiOnly: Boolean) {
        val current = AppSettingsStore.current(context)
        AppSettingsStore.save(
            context,
            current.copy(
                seriesAutomationEnabled = enabled,
                seriesAutoDownload = autoDownload,
                seriesWifiOnly = wifiOnly
            )
        )
        schedule(context)
    }

    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        if (!enabled(context)) { wm.cancelUniqueWork(WORK); return }
        val network = if (wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequestBuilder<SeriesWatchWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

internal class SeriesWatchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!SeriesAutomationPrefs.enabled(applicationContext)) return Result.success()
        return runCatching {
            val library = LibraryRepository.snapshot(applicationContext)
            var changed = false
            var addedCount = 0
            val newTitles = mutableListOf<String>()
            val updated = library.map { item ->
                val series = item.series
                val seriesName = series.name.ifBlank { "Серія" }
                if (series.url.isBlank()) return@map item
                val remote = AudiobooFastParser.parseSeries(series.url) ?: return@map item
                val known = item.books.mapTo(mutableSetOf()) { it.url }
                val books = item.books.sortedBy { it.sortIndex }.toMutableList()
                for (book in remote) {
                    CoverCache.enqueue(applicationContext, book.coverUrl)
                    if (!known.add(book.url)) continue
                    var archive: String? = null
                    if (SeriesAutomationPrefs.autoDownload(applicationContext)) {
                        archive = AudiobooFastParser.findArchive(book.url)
                    }
                    books += BookEntity(
                        id = "${series.id}::${book.url}",
                        seriesId = series.id,
                        title = book.title,
                        url = book.url,
                        author = book.author,
                        coverUrl = book.coverUrl,
                        status = "NEW",
                        archiveUrl = archive,
                        sortIndex = books.size
                    )
                    changed = true
                    addedCount++
                    newTitles += "$seriesName — ${book.title}"
                    if (!archive.isNullOrBlank()) {
                        ManagedDownloads.enqueue(applicationContext, book.title, seriesName, book.author, book.url, archive)
                    }
                }
                item.copy(books = books)
            }
            if (changed) LibraryRepository.replaceAll(applicationContext, updated)
            if (addedCount > 0) notifyNewBooks(applicationContext, addedCount, newTitles.take(3))
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun notifyNewBooks(context: Context, count: Int, titles: List<String>) {
        val channelId = "audoiboo_new_books"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Нові аудіокниги", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val text = if (count == 1) titles.firstOrNull().orEmpty() else "Знайдено нових книг: $count"
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("Audoiboo Tracker")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles.joinToString("\n")))
            .setAutoCancel(true)
            .build()
        nm.notify(4201, notification)
    }
}
