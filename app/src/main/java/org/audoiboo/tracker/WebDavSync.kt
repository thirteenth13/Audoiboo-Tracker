package org.audoiboo.tracker

import android.content.Context
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal object WebDavSync {
    private const val PREFS = "webdav_sync"
    private const val URL_KEY = "url"
    private const val USER_KEY = "user"
    private const val PASS_KEY = "pass"
    private const val ENABLED = "enabled"
    private const val WORK = "audoiboo-webdav-sync"
    private const val FILE = "Audoiboo-Tracker-backup.json"

    fun url(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(URL_KEY, "").orEmpty()
    fun user(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(USER_KEY, "").orEmpty()
    fun password(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PASS_KEY, "").orEmpty()
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun save(context: Context, url: String, user: String, pass: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(URL_KEY, url.trim()).putString(USER_KEY, user.trim()).putString(PASS_KEY, pass)
            .putBoolean(ENABLED, enabled).apply()
        schedule(context)
    }

    fun schedule(context: Context) {
        if (!enabled(context) || url(context).isBlank()) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<WebDavWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun upload(context: Context) = withContext(Dispatchers.IO) {
        val base = url(context).trimEnd('/'); if (base.isBlank()) error("Не вказано WebDAV URL")
        val conn = connection(context, "$base/$FILE", "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val backup = BackupStore.exportJsonFromRoom(context.applicationContext)
            conn.outputStream.bufferedWriter().use { it.write(backup) }
            val code = conn.responseCode
            if (code !in 200..299) error("WebDAV HTTP $code")
        } finally { conn.disconnect() }
    }

    suspend fun download(context: Context) = withContext(Dispatchers.IO) {
        val base = url(context).trimEnd('/'); if (base.isBlank()) error("Не вказано WebDAV URL")
        val conn = connection(context, "$base/$FILE", "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("WebDAV HTTP $code")
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            BackupStore.importJson(context, raw)
        } finally { conn.disconnect() }
    }

    private fun connection(context: Context, target: String, method: String): HttpURLConnection {
        return (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20_000; readTimeout = 30_000
            val u = user(context); val p = password(context)
            if (u.isNotBlank()) {
                val token = Base64.encodeToString("$u:$p".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
        }
    }
}

internal class WebDavWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        if (!WebDavSync.enabled(applicationContext)) return Result.success()
        WebDavSync.upload(applicationContext)
        Result.success()
    }.getOrElse { Result.retry() }
}
