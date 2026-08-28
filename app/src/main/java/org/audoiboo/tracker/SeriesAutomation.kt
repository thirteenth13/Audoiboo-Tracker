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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal object SeriesAutomationPrefs {
    private const val PREFS = "series_automation"
    private const val ENABLED = "enabled"
    private const val AUTO_DOWNLOAD = "auto_download"
    private const val WIFI_ONLY = "wifi_only"
    private const val WORK = "audoiboo-series-watch"

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun autoDownload(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AUTO_DOWNLOAD, false)
    fun wifiOnly(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WIFI_ONLY, true)

    fun save(context: Context, enabled: Boolean, autoDownload: Boolean, wifiOnly: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, enabled).putBoolean(AUTO_DOWNLOAD, autoDownload).putBoolean(WIFI_ONLY, wifiOnly).apply()
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
            val prefs = applicationContext.getSharedPreferences("tracker", Context.MODE_PRIVATE)
            val raw = prefs.getString("library", "[]") ?: "[]"
            val root = JSONArray(raw)
            var changed = false
            var addedCount = 0
            val newTitles = mutableListOf<String>()

            for (i in 0 until root.length()) {
                val series = root.optJSONObject(i) ?: continue
                val seriesUrl = series.optString("url")
                val seriesName = series.optString("name").ifBlank { "Серія" }
                if (seriesUrl.isBlank()) continue
                val remote = AudiobooFastParser.parseSeries(seriesUrl) ?: continue
                val books = series.optJSONArray("books") ?: JSONArray().also { series.put("books", it) }
                val known = (0 until books.length()).mapNotNull { books.optJSONObject(it)?.optString("url")?.takeIf(String::isNotBlank) }.toMutableSet()
                for (book in remote) {
                    CoverCache.enqueue(applicationContext, book.coverUrl)
                    if (!known.add(book.url)) continue
                    var archive: String? = null
                    if (SeriesAutomationPrefs.autoDownload(applicationContext)) archive = AudiobooFastParser.findArchive(book.url)
                    val obj = JSONObject()
                        .put("title", book.title).put("url", book.url).put("author", book.author)
                        .put("coverUrl", book.coverUrl).put("status", "NEW").put("archiveUrl", archive)
                    books.put(obj); changed = true; addedCount++; newTitles += "$seriesName — ${book.title}"
                    if (!archive.isNullOrBlank()) {
                        ManagedDownloads.enqueue(applicationContext, book.title, seriesName, book.author, book.url, archive)
                    }
                }
            }
            if (changed) prefs.edit().putString("library", root.toString()).apply()
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