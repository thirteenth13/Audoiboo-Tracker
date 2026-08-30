package org.audoiboo.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class ManagedDownloadService : Service() {
    companion object {
        const val ACTION_START = "org.audoiboo.tracker.download.START"
        const val ACTION_PAUSE = "org.audoiboo.tracker.download.PAUSE"
        const val ACTION_RESUME = "org.audoiboo.tracker.download.RESUME"
        const val ACTION_CANCEL = "org.audoiboo.tracker.download.CANCEL"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "audoiboo_downloads"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_CONCURRENT_DOWNLOADS = 2
        private val pauses = ConcurrentHashMap<String, AtomicBoolean>()
        private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
        private val running = ActiveDownloadRegistry<Thread>()
        private val starts = DownloadStartCoordinator(MAX_CONCURRENT_DOWNLOADS)
    }

    override fun onCreate() {
        super.onCreate()
        ManagedDownloads.initialize(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Audoiboo Tracker", "Керування завантаженнями"))
        val id = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_PAUSE -> {
                pauses.getOrPut(id) { AtomicBoolean(false) }.set(true)
                starts.cancelQueued(id)
                maybeStopForeground()
            }
            ACTION_CANCEL -> {
                cancels.getOrPut(id) { AtomicBoolean(false) }.set(true)
                starts.cancelQueued(id)
                maybeStopForeground()
            }
            ACTION_START, ACTION_RESUME -> {
                val record = ManagedDownloads.get(this, id) ?: return START_NOT_STICKY
                if (DownloadControlPolicy.canStart(record.state) && starts.request(id)) launchReserved(id)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun launchReserved(id: String) {
        val record = ManagedDownloads.get(this, id)
        if (record == null || !DownloadControlPolicy.canStart(record.state)) {
            promoteAfter(id)
            return
        }
        lateinit var thread: Thread
        thread = Thread {
            try {
                performDownload(id)
            } finally {
                running.unregister(id, thread)
                pauses.remove(id)
                cancels.remove(id)
                promoteAfter(id)
            }
        }.apply { name = "Audoiboo-$id" }
        if (!running.tryRegister(id, thread)) {
            promoteAfter(id)
            return
        }
        pauses.getOrPut(id) { AtomicBoolean(false) }.set(false)
        cancels.getOrPut(id) { AtomicBoolean(false) }.set(false)
        try {
            thread.start()
        } catch (e: RuntimeException) {
            running.unregister(id, thread)
            pauses.remove(id)
            cancels.remove(id)
            promoteAfter(id)
            throw e
        }
    }

    private fun promoteAfter(id: String) {
        val next = starts.finished(id)
        if (next != null) launchReserved(next) else maybeStopForeground()
    }

    private fun maybeStopForeground() {
        if (running.isEmpty() && starts.activeCount() == 0 && starts.queuedCount() == 0) {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    private fun performDownload(id: String) {
        var record = ManagedDownloads.get(this, id) ?: return
        if (!DownloadControlPolicy.canStart(record.state)) return
        val stagingRoot = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging").apply { mkdirs() }
        val part = File(stagingRoot, "$id.part")
        var existing = part.length()
        if (DownloadStagingPolicy.shouldDiscard(existing, record.total)) {
            part.delete()
            existing = 0L
        }
        val actualProgress = DownloadStagingPolicy.actualProgress(existing, record.total)
        if (record.downloaded != actualProgress) {
            record = record.copy(downloaded = actualProgress)
            update(record)
        }

        var conn: HttpURLConnection? = null
        try {
            ensureRunning(id)
            if (DownloadStagingPolicy.isComplete(existing, record.total)) {
                finishDownloadedPart(record.copy(downloaded = existing, error = null), part, id)
                return
            }

            conn = (URL(record.archiveUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36")
                setRequestProperty("Referer", record.bookUrl)
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            conn.connect()
            ensureRunning(id)
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val contentRange = conn.getHeaderField("Content-Range")
            val append = DownloadResumePolicy.canAppend(existing, code, contentRange)
            if (existing > 0 && !append) {
                part.delete()
                existing = 0L
            }
            val startAt = if (append) existing else 0L
            val contentLength = conn.contentLengthLong
            val total = DownloadResumePolicy.expectedTotal(startAt, contentLength, contentRange)
            record = record.copy(state = ManagedDownloadState.DOWNLOADING, downloaded = startAt, total = total, error = null)
            update(record)

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = startAt
                    var lastSaved = downloaded
                    while (true) {
                        ensureRunning(id)
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastSaved >= 512 * 1024) {
                            lastSaved = downloaded
                            update(record.copy(state = ManagedDownloadState.DOWNLOADING, downloaded = downloaded, total = total))
                        }
                    }
                    record = record.copy(downloaded = downloaded, total = total)
                }
            }

            ensureRunning(id)
            if (record.total > 0 && part.length() != record.total) {
                error("Неповне завантаження: ${part.length()} із ${record.total} байт")
            }
            finishDownloadedPart(record, part, id)
        } catch (e: DownloadStoppedException) {
            if (cancels[id]?.get() == true) {
                part.delete()
                update(record.copy(state = ManagedDownloadState.CANCELLED, downloaded = 0L))
            } else if (pauses[id]?.get() == true) {
                update(record.copy(state = ManagedDownloadState.PAUSED, downloaded = part.length()))
            }
        } catch (e: Exception) {
            if (cancels[id]?.get() == true) {
                part.delete()
                update(record.copy(state = ManagedDownloadState.CANCELLED, downloaded = 0L))
            } else if (pauses[id]?.get() == true) {
                update(record.copy(state = ManagedDownloadState.PAUSED, downloaded = part.length()))
            } else {
                update(record.copy(
                    state = ManagedDownloadState.FAILED,
                    error = e.message ?: e.javaClass.simpleName,
                    downloaded = part.length()
                ))
                DownloadScheduler.enqueue(this, id, delayedRetry = true)
            }
        } finally {
            conn?.disconnect()
        }
    }

    private fun finishDownloadedPart(record: ManagedDownloadRecord, part: File, id: String) {
        var current = record.copy(downloaded = part.length())
        val bookRelativeDir = "${current.relativeDir}/${current.bookDir}"
        if (AppPrefs.unpack(this) && current.fileName.endsWith(".zip", true)) {
            current = current.copy(state = ManagedDownloadState.EXTRACTING)
            update(current)
            try {
                verifyZipIntegrity(part, id)
            } catch (e: DownloadStoppedException) {
                throw e
            } catch (e: Exception) {
                part.delete()
                throw IOException("ZIP пошкоджений: ${e.message ?: "CRC/структура"}", e)
            }
            ensureRunning(id)
            clearBookFolder(current)
            extractZipToDownloads(part, bookRelativeDir, current)
            part.delete()
        } else {
            publishFile(part, bookRelativeDir, current.fileName, current)
            part.delete()
        }
        update(current.copy(
            state = ManagedDownloadState.COMPLETED,
            downloaded = if (current.total > 0) current.total else current.downloaded,
            error = null
        ))
        DownloadScheduler.cancel(this, id)
    }

    private fun ensureRunning(id: String) {
        if (cancels[id]?.get() == true || pauses[id]?.get() == true) throw DownloadStoppedException()
    }

    private fun verifyZipIntegrity(file: File, id: String) {
        var files = 0
        var uncompressedTotal = 0L
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(128 * 1024)
            while (entries.hasMoreElements()) {
                ensureRunning(id)
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (ArchiveEntryPolicy.safeRelativePath(entry.name) == null) error("Небезпечний шлях у ZIP: ${entry.name}")
                if (!ArchiveSafetyPolicy.validateDeclaredEntrySize(entry.size)) error("Файл у ZIP завеликий: ${entry.name}")
                files++
                if (!ArchiveSafetyPolicy.validateTotals(files, uncompressedTotal)) error("ZIP перевищує безпечні ліміти")
                val crc = CRC32()
                var entryBytes = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        ensureRunning(id)
                        val n = input.read(buffer)
                        if (n < 0) break
                        entryBytes += n
                        uncompressedTotal += n
                        if (entryBytes > ArchiveSafetyPolicy.MAX_SINGLE_FILE_BYTES) error("Файл у ZIP завеликий: ${entry.name}")
                        if (!ArchiveSafetyPolicy.validateTotals(files, uncompressedTotal)) error("ZIP перевищує безпечний розпакований обсяг")
                        crc.update(buffer, 0, n)
                    }
                }
                if (entry.crc >= 0 && crc.value != entry.crc) error("CRC не збігається: ${entry.name}")
            }
        }
        if (files == 0) error("архів не містить файлів")
    }

    private fun clearBookFolder(record: ManagedDownloadRecord) {
        if (StorageAccess.treeUri(this) != null) {
            StorageAccess.clearDirectory(this, "${record.relativeDir}/${record.bookDir}")
            return
        }
        val target = "Download/${record.relativeDir}/${record.bookDir}/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$target%")) }
            runCatching { contentResolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("$target%")) }
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${record.relativeDir}/${record.bookDir}").deleteRecursively()
        }
    }

    private fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in
        setOf("mp3", "m4a", "m4b", "ogg", "opus", "wav", "aac", "flac")

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun publishFile(source: File, relativeDir: String, fileName: String, record: ManagedDownloadRecord) {
        ensureRunning(record.id)
        if (StorageAccess.treeUri(this) != null) {
            val (uri, out) = StorageAccess.openOutput(this, relativeDir, fileName, mimeFor(fileName))
                ?: error("Не вдалося створити файл у вибраній SAF-папці")
            out.use { output -> FileInputStream(source).use { input -> copyInterruptibly(input, output, record.id) } }
            if (isAudio(fileName)) PlayerLibrary.register(this, uri, fileName, relativeDir, record.title, record.series, record.author)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$relativeDir")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не вдалося створити файл")
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(source).use { input -> copyInterruptibly(input, out, record.id) }
                } ?: error("Не вдалося відкрити файл")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                if (isAudio(fileName)) PlayerLibrary.register(this, uri, fileName, "Download/$relativeDir", record.title, record.series, record.author)
            } catch (e: Exception) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativeDir).apply { mkdirs() }
            val target = File(dir, fileName)
            try {
                FileInputStream(source).use { input -> FileOutputStream(target).use { output -> copyInterruptibly(input, output, record.id) } }
                if (isAudio(fileName)) PlayerLibrary.register(this, Uri.fromFile(target), fileName, target.parent.orEmpty(), record.title, record.series, record.author)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }

    private fun extractZipToDownloads(zipFile: File, relativeDir: String, record: ManagedDownloadRecord) {
        val budget = ArchiveExtractionBudget()
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                ensureRunning(record.id)
                if (!entry.isDirectory) {
                    val safe = ArchiveEntryPolicy.safeRelativePath(entry.name)
                        ?: throw IOException("Небезпечний шлях у ZIP: ${entry.name}")
                    if (!ArchiveSafetyPolicy.validateDeclaredEntrySize(entry.size)) {
                        throw IOException("Файл у ZIP завеликий: ${entry.name}")
                    }
                    budget.beginEntry()
                    publishStream(zin, relativeDir, safe, record, budget)
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    private fun publishStream(
        input: InputStream,
        relativeDir: String,
        nestedPath: String,
        record: ManagedDownloadRecord,
        budget: ArchiveExtractionBudget
    ) {
        val safePath = ArchiveEntryPolicy.safeRelativePath(nestedPath)
            ?: throw IOException("Небезпечний шлях у ZIP: $nestedPath")
        val normalized = safePath.replace('\\', '/')
        val parent = normalized.substringBeforeLast('/', "")
        val fileName = normalized.substringAfterLast('/')
        val dir = if (parent.isBlank()) relativeDir else "$relativeDir/$parent"
        if (StorageAccess.treeUri(this) != null) {
            val (uri, out) = StorageAccess.openOutput(this, dir, fileName, mimeFor(fileName))
                ?: error("Не вдалося створити файл у вибраній SAF-папці")
            try {
                out.use { output -> copyArchiveEntry(input, output, record.id, budget) }
                if (isAudio(fileName)) PlayerLibrary.register(this, uri, fileName, dir, record.title, record.series, record.author)
            } catch (e: Exception) {
                runCatching { StorageAccess.delete(this, dir, fileName) }
                throw e
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$dir")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не вдалося створити файл")
            try {
                contentResolver.openOutputStream(uri)?.use { out -> copyArchiveEntry(input, out, record.id, budget) }
                    ?: error("Не вдалося відкрити файл")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                if (isAudio(fileName)) PlayerLibrary.register(this, uri, fileName, "Download/$dir", record.title, record.series, record.author)
            } catch (e: Exception) {
                runCatching { contentResolver.delete(uri, null, null) }
                throw e
            }
        } else {
            val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), dir).apply { mkdirs() }
            val target = File(targetDir, fileName)
            try {
                FileOutputStream(target).use { output -> copyArchiveEntry(input, output, record.id, budget) }
                if (isAudio(fileName)) PlayerLibrary.register(this, Uri.fromFile(target), fileName, target.parent.orEmpty(), record.title, record.series, record.author)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }

    private fun copyArchiveEntry(input: InputStream, output: OutputStream, id: String, budget: ArchiveExtractionBudget) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            ensureRunning(id)
            val read = input.read(buffer)
            if (read < 0) break
            budget.addBytes(read)
            output.write(buffer, 0, read)
        }
    }

    private fun copyInterruptibly(input: InputStream, output: OutputStream, id: String) {
        val buffer = ByteArray(128 * 1024)
        while (true) {
            ensureRunning(id)
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }

    private fun update(record: ManagedDownloadRecord) {
        ManagedDownloads.saveOne(this, record)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(record.id.hashCode(), notification(record.title, status(record)))
    }

    private fun status(r: ManagedDownloadRecord): String = when (r.state) {
        ManagedDownloadState.QUEUED -> "У черзі"
        ManagedDownloadState.DOWNLOADING -> if (r.total > 0) "${r.downloaded * 100 / r.total}%" else "Завантаження"
        ManagedDownloadState.PAUSED -> "Призупинено"
        ManagedDownloadState.EXTRACTING -> "Розпакування"
        ManagedDownloadState.COMPLETED -> "Готово"
        ManagedDownloadState.FAILED -> "Помилка: ${r.error.orEmpty()}"
        ManagedDownloadState.CANCELLED -> "Скасовано"
    }

    private fun notification(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, RoomLibraryActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Завантаження аудіокниг", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

private class DownloadStoppedException : RuntimeException()
