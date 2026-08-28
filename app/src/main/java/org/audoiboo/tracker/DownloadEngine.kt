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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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
    private const val PREFS = "managed_downloads"
    private const val KEY = "items"

    fun enqueue(context: Context, title: String, series: String, author: String?, bookUrl: String, archiveUrl: String) {
        val base = cleanPath(AppPrefs.baseFolder(context))
        val parts = mutableListOf(base)
        if (AppPrefs.useAuthorFolder(context) && !author.isNullOrBlank()) parts += cleanPath(author)
        parts += cleanPath(series)
        val relativeDir = parts.joinToString("/")
        val bookDir = cleanPath(title).take(120)
        val fileName = bookDir + archiveExtension(archiveUrl)
        val record = ManagedDownloadRecord(UUID.randomUUID().toString(), title, series, author, bookUrl, archiveUrl, relativeDir, bookDir, fileName, ManagedDownloadState.QUEUED)
        saveOne(context, record)
        DownloadScheduler.enqueue(context, record.id)
    }

    fun pause(context: Context, id: String) {
        DownloadScheduler.cancel(context, id)
        send(context, ManagedDownloadService.ACTION_PAUSE, id)
    }

    fun resume(context: Context, id: String) {
        get(context, id)?.let { saveOne(context, it.copy(state = ManagedDownloadState.QUEUED, error = null)) }
        DownloadScheduler.enqueue(context, id)
    }

    fun cancel(context: Context, id: String) {
        DownloadScheduler.cancel(context, id)
        send(context, ManagedDownloadService.ACTION_CANCEL, id)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        DownloadScheduler.cancel(context, id)
        save(context, list(context).filterNot { it.id == id })
        File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging/$id.part").delete()
    }

    fun list(context: Context): List<ManagedDownloadRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }.sortedByDescending { it.createdAt }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, id: String) = list(context).firstOrNull { it.id == id }

    @Synchronized
    fun saveOne(context: Context, record: ManagedDownloadRecord) {
        val items = list(context).toMutableList()
        val i = items.indexOfFirst { it.id == record.id }
        if (i >= 0) items[i] = record else items += record
        save(context, items)
    }

    private fun save(context: Context, items: List<ManagedDownloadRecord>) {
        val arr = JSONArray(); items.take(200).forEach { arr.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun send(context: Context, action: String, id: String) {
        val intent = Intent(context, ManagedDownloadService::class.java).setAction(action).putExtra(ManagedDownloadService.EXTRA_ID, id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun toJson(r: ManagedDownloadRecord) = JSONObject()
        .put("id", r.id).put("title", r.title).put("series", r.series).put("author", r.author)
        .put("bookUrl", r.bookUrl).put("archiveUrl", r.archiveUrl).put("relativeDir", r.relativeDir)
        .put("bookDir", r.bookDir).put("fileName", r.fileName).put("state", r.state.name).put("downloaded", r.downloaded)
        .put("total", r.total).put("error", r.error).put("createdAt", r.createdAt)

    private fun fromJson(o: JSONObject): ManagedDownloadRecord {
        val title = o.optString("title")
        val fallbackBookDir = cleanPath(title).take(120)
        return ManagedDownloadRecord(
            o.optString("id"), title, o.optString("series"), o.optString("author").takeIf { it.isNotBlank() && it != "null" },
            o.optString("bookUrl"), o.optString("archiveUrl"), o.optString("relativeDir"),
            o.optString("bookDir").takeIf { it.isNotBlank() } ?: fallbackBookDir,
            o.optString("fileName"),
            runCatching { ManagedDownloadState.valueOf(o.optString("state")) }.getOrDefault(ManagedDownloadState.FAILED),
            o.optLong("downloaded"), o.optLong("total", -1L), o.optString("error").takeIf { it.isNotBlank() && it != "null" }, o.optLong("createdAt")
        )
    }

    internal fun cleanPath(v: String) = v.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "Unknown" }
    private fun archiveExtension(url: String) = Regex("\\.(zip|rar|7z)(?:\\?|$)", RegexOption.IGNORE_CASE).find(url)?.value?.substringBefore('?') ?: ".zip"
}

class ManagedDownloadService : Service() {
    companion object {
        const val ACTION_START = "org.audoiboo.tracker.download.START"
        const val ACTION_PAUSE = "org.audoiboo.tracker.download.PAUSE"
        const val ACTION_RESUME = "org.audoiboo.tracker.download.RESUME"
        const val ACTION_CANCEL = "org.audoiboo.tracker.download.CANCEL"
        const val EXTRA_ID = "id"
        private const val CHANNEL = "audoiboo_downloads"
        private const val NOTIFICATION_ID = 4102
        private val pauses = ConcurrentHashMap<String, AtomicBoolean>()
        private val cancels = ConcurrentHashMap<String, AtomicBoolean>()
        private val running = ConcurrentHashMap<String, Thread>()
    }

    override fun onCreate() { super.onCreate(); createChannel() }
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification("Audoiboo Tracker", "Керування завантаженнями"))
        val id = intent?.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY
        when (intent.action) {
            ACTION_PAUSE -> pauses.getOrPut(id) { AtomicBoolean(false) }.set(true)
            ACTION_CANCEL -> {
                cancels.getOrPut(id) { AtomicBoolean(false) }.set(true)
                ManagedDownloads.get(this, id)?.let { update(it.copy(state = ManagedDownloadState.CANCELLED)) }
            }
            ACTION_START, ACTION_RESUME -> startDownload(id)
        }
        return START_REDELIVER_INTENT
    }

    private fun startDownload(id: String) {
        if (running[id]?.isAlive == true) return
        pauses.getOrPut(id) { AtomicBoolean(false) }.set(false)
        cancels.getOrPut(id) { AtomicBoolean(false) }.set(false)
        val thread = Thread {
            try { performDownload(id) }
            finally {
                running.remove(id)
                if (running.isEmpty()) stopForeground(STOP_FOREGROUND_DETACH)
            }
        }.apply { name = "Audoiboo-$id"; start() }
        running[id] = thread
    }

    private fun performDownload(id: String) {
        var record = ManagedDownloads.get(this, id) ?: return
        val stagingRoot = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "staging").apply { mkdirs() }
        val part = File(stagingRoot, "$id.part")
        val existing = part.length()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(record.archiveUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome Mobile Safari/537.36")
                setRequestProperty("Referer", record.bookUrl)
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            val append = existing > 0 && code == HttpURLConnection.HTTP_PARTIAL
            if (existing > 0 && !append) part.delete()
            val startAt = if (append) existing else 0L
            val contentLength = conn.contentLengthLong
            val total = if (contentLength > 0) startAt + contentLength else -1L
            record = record.copy(state = ManagedDownloadState.DOWNLOADING, downloaded = startAt, total = total, error = null)
            update(record)

            BufferedInputStream(conn.inputStream).use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = startAt
                    var lastSaved = downloaded
                    while (true) {
                        if (cancels[id]?.get() == true) {
                            part.delete()
                            update(record.copy(state = ManagedDownloadState.CANCELLED, downloaded = downloaded, total = total))
                            return
                        }
                        if (pauses[id]?.get() == true) {
                            update(record.copy(state = ManagedDownloadState.PAUSED, downloaded = downloaded, total = total))
                            return
                        }
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

            if (record.total > 0 && part.length() != record.total) {
                error("Неповне завантаження: ${part.length()} із ${record.total} байт")
            }

            if (AppPrefs.unpack(this) && record.fileName.endsWith(".zip", true)) {
                update(record.copy(state = ManagedDownloadState.EXTRACTING))
                try {
                    verifyZipIntegrity(part)
                } catch (e: Exception) {
                    part.delete()
                    throw IOException("ZIP пошкоджений: ${e.message ?: "CRC/структура"}", e)
                }
                clearBookFolder(record)
                extractZipToDownloads(part, "${record.relativeDir}/${record.bookDir}", record)
                part.delete()
            } else {
                publishFile(part, record.relativeDir, record.fileName, record)
                part.delete()
            }
            update(record.copy(state = ManagedDownloadState.COMPLETED, downloaded = if (record.total > 0) record.total else record.downloaded, error = null))
            DownloadScheduler.cancel(this, id)
        } catch (e: Exception) {
            if (cancels[id]?.get() != true && pauses[id]?.get() != true) {
                update(record.copy(state = ManagedDownloadState.FAILED, error = e.message ?: e.javaClass.simpleName, downloaded = part.length()))
                DownloadScheduler.enqueue(this, id, delayedRetry = true)
            }
        } finally { conn?.disconnect() }
    }

    private fun verifyZipIntegrity(file: File) {
        var files = 0
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            val buffer = ByteArray(128 * 1024)
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                files++
                val crc = CRC32()
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        crc.update(buffer, 0, n)
                    }
                }
                if (entry.crc >= 0 && crc.value != entry.crc) error("CRC не збігається: ${entry.name}")
            }
        }
        if (files == 0) error("архів не містить файлів")
    }

    private fun clearBookFolder(record: ManagedDownloadRecord) {
        val target = "Download/${record.relativeDir}/${record.bookDir}/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?", arrayOf("$target%")) }
            runCatching { contentResolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?", arrayOf("$target%")) }
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "${record.relativeDir}/${record.bookDir}").deleteRecursively()
        }
    }

    private fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase() in setOf("mp3", "m4a", "m4b", "ogg", "opus", "wav", "aac", "flac")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$relativeDir")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Не вдалося створити файл")
            contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(source).use { it.copyTo(out) } } ?: error("Не вдалося відкрити файл")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
            if (isAudio(fileName)) PlayerLibrary.register(this, uri, fileName, "Download/$relativeDir", record.title, record.series, record.author)
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), relativeDir).apply { mkdirs() }
            val target = File(dir, fileName)
            source.copyTo(target, overwrite = true)
            if (isAudio(fileName)) PlayerLibrary.register(this, Uri.fromFile(target), fileName, target.parent.orEmpty(), record.title, record.series, record.author)
        }
    }

    private fun extractZipToDownloads(zipFile: File, relativeDir: String, record: ManagedDownloadRecord) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val safe = entry.name.replace('\\', '/').split('/').filter { it.isNotBlank() && it != ".." }.joinToString("/")
                    if (safe.isNotBlank()) publishStream(zin, relativeDir, safe, record)
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
    }

    private fun publishStream(input: InputStream, relativeDir: String, nestedName: String, record: ManagedDownloadRecord) {
        val sub = nestedName.substringBeforeLast('/', "")
        val file = nestedName.substringAfterLast('/')
        val dir = listOf(relativeDir, sub).filter { it.isNotBlank() }.joinToString("/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file)
                put(MediaStore.Downloads.MIME_TYPE, mimeFor(file))
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$dir")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
            if (isAudio(file)) PlayerLibrary.register(this, uri, file, "Download/$dir", record.title, record.series, record.author)
        } else {
            val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), dir).apply { mkdirs() }
            val target = File(targetDir, file)
            FileOutputStream(target).use { input.copyTo(it) }
            if (isAudio(file)) PlayerLibrary.register(this, Uri.fromFile(target), file, target.parent.orEmpty(), record.title, record.series, record.author)
        }
    }

    private fun update(record: ManagedDownloadRecord) {
        ManagedDownloads.saveOne(this, record)
        val pct = if (record.total > 0) (record.downloaded * 100 / record.total).toInt() else -1
        val detail = when (record.state) {
            ManagedDownloadState.DOWNLOADING -> if (pct >= 0) "${record.title} — $pct%" else record.title
            ManagedDownloadState.EXTRACTING -> "Перевірка/розпакування: ${record.title}"
            ManagedDownloadState.PAUSED -> "Призупинено: ${record.title}"
            ManagedDownloadState.FAILED -> "Помилка: ${record.title}"
            else -> record.title
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification("Audoiboo Tracker", detail))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Завантаження аудіокниг", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(title: String, text: String): android.app.Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}

@Composable
internal fun ManagedDownloadsScreen(context: Context) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val records = remember(tick) { ManagedDownloads.list(context) }
    if (records.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Тут з’являться завантаження аудіокниг.") }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(records, key = { it.id }) { r ->
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(r.author, r.series).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                        }
                        when (r.state) {
                            ManagedDownloadState.DOWNLOADING, ManagedDownloadState.QUEUED -> IconButton(onClick = { ManagedDownloads.pause(context, r.id) }) { Icon(Icons.Filled.Pause, "Пауза") }
                            ManagedDownloadState.PAUSED, ManagedDownloadState.FAILED -> IconButton(onClick = { ManagedDownloads.resume(context, r.id) }) { Icon(Icons.Filled.PlayArrow, "Продовжити") }
                            ManagedDownloadState.COMPLETED -> IconButton(onClick = {
                                val dir = if (AppPrefs.unpack(context) && r.fileName.endsWith(".zip", true)) "${r.relativeDir}/${r.bookDir}" else r.relativeDir
                                context.startActivity(Intent(context, PlayerActivity::class.java).putExtra("relativeDir", dir).putExtra("title", r.title))
                            }) { Icon(Icons.Filled.Headphones, "Відкрити в плеєрі") }
                            else -> Unit
                        }
                        if (r.state !in listOf(ManagedDownloadState.COMPLETED, ManagedDownloadState.CANCELLED)) IconButton(onClick = { ManagedDownloads.cancel(context, r.id) }) { Icon(Icons.Filled.Cancel, "Скасувати") }
                        if (r.state == ManagedDownloadState.CANCELLED) IconButton(onClick = { ManagedDownloads.remove(context, r.id); tick++ }) { Icon(Icons.Filled.Delete, "Видалити") }
                    }
                    val progress = if (r.total > 0) r.downloaded.toFloat() / r.total else 0f
                    if (r.state in listOf(ManagedDownloadState.DOWNLOADING, ManagedDownloadState.PAUSED, ManagedDownloadState.EXTRACTING)) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                    Text(stateLabel(r), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    val displayPath = if (AppPrefs.unpack(context) && r.fileName.endsWith(".zip", true)) "Downloads/${r.relativeDir}/${r.bookDir}/" else "Downloads/${r.relativeDir}/${r.fileName}"
                    Text(displayPath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun stateLabel(r: ManagedDownloadRecord): String {
    val pct = if (r.total > 0) " ${(r.downloaded * 100 / r.total)}%" else ""
    return when (r.state) {
        ManagedDownloadState.QUEUED -> "Очікує умов мережі"
        ManagedDownloadState.DOWNLOADING -> "Завантажується$pct"
        ManagedDownloadState.PAUSED -> "Пауза$pct"
        ManagedDownloadState.EXTRACTING -> "Перевіряється та розпаковується"
        ManagedDownloadState.COMPLETED -> "Готово"
        ManagedDownloadState.FAILED -> "Помилка — буде повторна спроба"
        ManagedDownloadState.CANCELLED -> "Скасовано"
    }
}
