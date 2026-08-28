package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal object CoverCache {
    private const val WORK_PREFIX = "audoiboo-cover-"

    private fun dir(context: Context) = File(context.filesDir, "covers").apply { mkdirs() }

    private fun name(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } + ".img"
    }

    fun file(context: Context, url: String): File = File(dir(context), name(url))

    fun cachedUri(context: Context, url: String?): Uri? {
        if (url.isNullOrBlank()) return null
        val f = file(context, url)
        if (!f.isFile || f.length() <= 0L) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun remoteUrlFor(context: Context, item: PlayerLibraryItem): String? {
        val title = item.bookTitle ?: item.relativePath.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        RoomCoverSync.lookup(context, item.series, title)?.let { return it }

        // Temporary compatibility fallback until the legacy tracker JSON is fully retired.
        val raw = context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", "[]") ?: return null
        val wantedTitle = normalize(title)
        val wantedSeries = normalize(item.series.orEmpty())
        return runCatching {
            val root = JSONArray(raw)
            var fallback: String? = null
            for (i in 0 until root.length()) {
                val series = root.optJSONObject(i) ?: continue
                val seriesName = normalize(series.optString("name"))
                val books = series.optJSONArray("books") ?: continue
                for (j in 0 until books.length()) {
                    val book = books.optJSONObject(j) ?: continue
                    val cover = book.optString("coverUrl").takeIf { it.startsWith("http", true) } ?: continue
                    if (normalize(book.optString("title")) == wantedTitle) {
                        if (wantedSeries.isBlank() || seriesName == wantedSeries) return@runCatching cover
                        if (fallback == null) fallback = cover
                    }
                }
            }
            fallback
        }.getOrNull()
    }

    fun bestUri(context: Context, item: PlayerLibraryItem): Uri? {
        val remote = remoteUrlFor(context, item) ?: return null
        return cachedUri(context, remote) ?: Uri.parse(remote)
    }

    fun enqueue(context: Context, url: String?) {
        if (url.isNullOrBlank() || !url.startsWith("http", true)) return
        if (file(context, url).isFile) return
        val request = OneTimeWorkRequestBuilder<CoverCacheWorker>()
            .setInputData(workDataOf(CoverCacheWorker.URL_KEY to url))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_PREFIX + name(url), ExistingWorkPolicy.KEEP, request)
    }

    fun enqueueTrackerCovers(context: Context) {
        val raw = context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", "[]") ?: return
        runCatching {
            val root = JSONArray(raw)
            for (i in 0 until root.length()) {
                val books = root.optJSONObject(i)?.optJSONArray("books") ?: continue
                for (j in 0 until books.length()) enqueue(context, books.optJSONObject(j)?.optString("coverUrl"))
            }
        }
    }

    fun prune(context: Context, maxFiles: Int = 500) {
        dir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.drop(maxFiles.coerceAtLeast(50))?.forEach { it.delete() }
    }

    internal fun download(context: Context, url: String) {
        val target = file(context, url)
        if (target.isFile && target.length() > 0L) return
        val temp = File(target.parentFile, target.name + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Audoiboo-Tracker")
        try {
            if (conn.responseCode !in 200..299) error("Cover HTTP ${conn.responseCode}")
            val type = conn.contentType.orEmpty().lowercase()
            if (type.isNotBlank() && !type.startsWith("image/")) error("Not an image: $type")
            conn.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            if (temp.length() <= 0L) error("Empty cover")
            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            prune(context)
        } finally {
            conn.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun normalize(value: String) = value.lowercase().replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ").trim()
}

internal class CoverCacheWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val url = inputData.getString(URL_KEY)?.takeIf { it.isNotBlank() } ?: return Result.failure()
        return runCatching {
            CoverCache.download(applicationContext, url)
            Result.success()
        }.getOrElse { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }

    companion object { const val URL_KEY = "url" }
}
