package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import java.io.File
import java.util.UUID

internal enum class ManagedDownloadState { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED, EXTRACTING }

internal data class ManagedDownloadRecord(
    val id: String,
    val title: String,
    val series: String,
    val author: String?,
    val bookUrl: String,
    val archiveUrl: String,
    val relativeDir: String,
    val bookDir: String,
    val fileName: String,
    val state: ManagedDownloadState,
    val downloaded: Long = 0L,
    val total: Long = -1L,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

internal object ManagedDownloads {
    fun initialize(context: Context) = ManagedDownloadRoomStore.initialize(context.applicationContext)

    fun enqueue(context: Context, title: String, series: String, author: String?, bookUrl: String, archiveUrl: String) {
        val base = cleanPath(AppPrefs.baseFolder(context))
        val parts = mutableListOf(base)
        if (AppPrefs.useAuthorFolder(context) && !author.isNullOrBlank()) parts += cleanPath(author)
        parts += cleanPath(series)
        val relativeDir = parts.joinToString("/")
        val bookDir = cleanPath(title).take(120)
        val fileName = bookDir + archiveExtension(archiveUrl)
        val record = ManagedDownloadRecord(
            UUID.randomUUID().toString(), title, series, author, bookUrl, archiveUrl,
            relativeDir, bookDir, fileName, ManagedDownloadState.QUEUED
        )
        saveOne(context, record)
        DownloadScheduler.enqueue(context, record.id)
    }

    fun pause(context: Context, id: String) {
        val record = get(context, id) ?: return
        val state = DownloadControlPolicy.pause(record.state)
        if (state != record.state) saveOne(context, record.copy(state = state))
        DownloadScheduler.cancel(context, id)
        if (state == ManagedDownloadState.PAUSED) send(context, ManagedDownloadService.ACTION_PAUSE, id)
    }

    fun resume(context: Context, id: String) {
        val record = get(context, id) ?: return
        val state = DownloadControlPolicy.resume(record.state)
        if (state != ManagedDownloadState.QUEUED) return
        saveOne(context, record.copy(state = state, error = null))
        DownloadScheduler.enqueue(context, id)
    }

    fun cancel(context: Context, id: String) {
        val record = get(context, id) ?: return
        val state = DownloadControlPolicy.cancel(record.state)
        if (state != record.state) saveOne(context, record.copy(state = state))
        DownloadScheduler.cancel(context, id)
        if (state == ManagedDownloadState.CANCELLED) send(context, ManagedDownloadService.ACTION_CANCEL, id)
    }

    fun remove(context: Context, id: String) {
        val record = get(context, id)
        DownloadScheduler.cancel(context, id)
        if (record?.state in setOf(
                ManagedDownloadState.QUEUED,
                ManagedDownloadState.DOWNLOADING,
                ManagedDownloadState.EXTRACTING
            )) {
            send(context, ManagedDownloadService.ACTION_CANCEL, id)
        }
        ManagedDownloadRoomStore.delete(context, id)
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging/$id.part").delete()
    }

    fun list(context: Context): List<ManagedDownloadRecord> = ManagedDownloadRoomStore.list(context)

    fun get(context: Context, id: String): ManagedDownloadRecord? = ManagedDownloadRoomStore.get(context, id)

    fun saveOne(context: Context, record: ManagedDownloadRecord) = ManagedDownloadRoomStore.save(context, record)

    private fun send(context: Context, action: String, id: String) {
        val intent = Intent(context, ManagedDownloadService::class.java).setAction(action).putExtra(ManagedDownloadService.EXTRA_ID, id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    internal fun cleanPath(v: String) = v.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "Unknown" }

    private fun archiveExtension(url: String) =
        Regex("\\.(zip|rar|7z)(?:\\?|$)", RegexOption.IGNORE_CASE)
            .find(url)?.value?.substringBefore('?') ?: ".zip"
}
