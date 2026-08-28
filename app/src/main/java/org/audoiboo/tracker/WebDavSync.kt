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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal class WebDavConflictException(message: String) : IllegalStateException(message)

internal object WebDavSync {
    private const val PREFS = "webdav_sync"
    private const val URL_KEY = "url"
    private const val USER_KEY = "user"
    private const val PASS_KEY = "pass"
    private const val ENABLED = "enabled"
    private const val ETAG_KEY = "etag"
    private const val WORK = "audoiboo-webdav-sync"
    private const val FILE = "Audoiboo-Tracker-backup.json"

    fun url(context: Context) = prefs(context).getString(URL_KEY, "").orEmpty()
    fun user(context: Context) = prefs(context).getString(USER_KEY, "").orEmpty()
    fun password(context: Context) = prefs(context).getString(PASS_KEY, "").orEmpty()
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)

    fun save(context: Context, url: String, user: String, pass: String, enabled: Boolean) {
        val p = prefs(context)
        val normalizedUrl = url.trim()
        val changedEndpoint = p.getString(URL_KEY, "").orEmpty() != normalizedUrl || p.getString(USER_KEY, "").orEmpty() != user.trim()
        val edit = p.edit()
            .putString(URL_KEY, normalizedUrl).putString(USER_KEY, user.trim()).putString(PASS_KEY, pass)
            .putBoolean(ENABLED, enabled)
        if (changedEndpoint) edit.remove(ETAG_KEY)
        edit.apply()
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

    fun upload(context: Context) {
        runBlocking(Dispatchers.IO) { uploadFromRoom(context.applicationContext) }
    }

    fun download(context: Context) {
        runBlocking(Dispatchers.IO) { downloadIntoRoom(context.applicationContext) }
    }

    private suspend fun uploadFromRoom(context: Context) = withContext(Dispatchers.IO) {
        val target = target(context)
        val conn = connection(context, target, "PUT")
        try {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            prefs(context).getString(ETAG_KEY, null)?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("If-Match", it) }
            val backup = BackupStore.exportJsonFromRoom(context)
            conn.outputStream.bufferedWriter().use { it.write(backup) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_PRECON_FAILED) {
                throw WebDavConflictException("WebDAV конфлікт: файл на сервері змінено іншим пристроєм. Спочатку віднови дані з WebDAV.")
            }
            if (code !in 200..299) error("WebDAV HTTP $code")
            val etag = conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotBlank() } ?: readRemoteEtag(context, target)
            if (!etag.isNullOrBlank()) prefs(context).edit().putString(ETAG_KEY, etag).apply()
        } finally { conn.disconnect() }
    }

    private suspend fun downloadIntoRoom(context: Context) = withContext(Dispatchers.IO) {
        val target = target(context)
        val conn = connection(context, target, "GET")
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("WebDAV HTTP $code")
            val etag = conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotBlank() }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            BackupStore.importJsonToRoom(context, raw)
            if (!etag.isNullOrBlank()) prefs(context).edit().putString(ETAG_KEY, etag).apply()
        } finally { conn.disconnect() }
    }

    private fun readRemoteEtag(context: Context, target: String): String? {
        val conn = connection(context, target, "HEAD")
        return try {
            if (conn.responseCode in 200..299) conn.getHeaderField("ETag")?.trim()?.takeIf { it.isNotBlank() } else null
        } finally { conn.disconnect() }
    }

    private fun target(context: Context): String {
        val base = url(context).trimEnd('/')
        if (base.isBlank()) error("Не вказано WebDAV URL")
        return "$base/$FILE"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun connection(context: Context, target: String, method: String): HttpURLConnection {
        return (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 30_000
            val u = user(context)
            val p = password(context)
            if (u.isNotBlank()) {
                val token = Base64.encodeToString("$u:$p".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                setRequestProperty("Authorization", "Basic $token")
            }
        }
    }
}

internal class WebDavWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (!WebDavSync.enabled(applicationContext)) return Result.success()
        return try {
            WebDavSync.upload(applicationContext)
            Result.success()
        } catch (_: WebDavConflictException) {
            Result.failure()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
