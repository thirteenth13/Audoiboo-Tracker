package org.audoiboo.tracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.util.Locale

internal data class AudioTrack(val uri: Uri, val name: String, val relativePath: String)
private data class AudioBookGroup(
    val title: String,
    val relativeDir: String,
    val tracks: List<AudioTrack>,
    val seriesName: String? = null,
    val seriesIndex: Int = Int.MAX_VALUE,
    val coverUrl: String? = null,
    val trackerStatus: String? = null
)
private data class TrackerBookMeta(val title: String, val series: String, val index: Int, val coverUrl: String?, val status: String?)
private data class BookProgress(val fraction: Float, val started: Boolean, val finished: Boolean)
private enum class LibraryFilter { ALL, NEW, STARTED, READ }

object PlayerPrefs {
    private const val FILE = "player_settings"
    fun seekSeconds(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("seek", 10)
    fun autoRewindSeconds(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("auto_rewind", 5)
    fun resumeAfterCall(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("resume_call", true)
    fun pauseOnNotifications(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("pause_notifications", false)
    fun stopOtherPlayers(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("stop_other", true)
    fun forceStopHours(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("force_hours", 2)
    fun showSpeed(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("show_speed", true)
    fun showSleep(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("show_sleep", true)
    fun showBookmarks(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("show_bookmarks", true)
    fun save(c: Context, seek:Int, rewind:Int, resume:Boolean, notifications:Boolean, other:Boolean, hours:Int, speed:Boolean, sleep:Boolean, bookmarks:Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("seek", seek).putInt("auto_rewind", rewind).putBoolean("resume_call", resume)
            .putBoolean("pause_notifications", notifications).putBoolean("stop_other", other).putInt("force_hours", hours)
            .putBoolean("show_speed", speed).putBoolean("show_sleep", sleep).putBoolean("show_bookmarks", bookmarks).apply()
    }
    fun position(c: Context, uri: Uri) = c.getSharedPreferences("player_positions", Context.MODE_PRIVATE).getLong(uri.toString(), 0L)
    fun savePosition(c: Context, uri: Uri, position: Long) { c.getSharedPreferences("player_positions", Context.MODE_PRIVATE).edit().putLong(uri.toString(), position).apply() }
}

private object PlayerQueueStore {
    private const val PREFS = "player_queue"
    private const val KEY = "book_dirs"
    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching { val a = JSONArray(raw); (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.getOrDefault(emptyList())
    }
    fun save(context: Context, dirs: List<String>) {
        val a = JSONArray(); dirs.distinct().forEach(a::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { PlayerScreen(this, intent.getStringExtra("relativeDir"), intent.getStringExtra("title")) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(activity: ComponentActivity, initialDir: String?, initialTitle: String?) {
    var refresh by remember { mutableIntStateOf(0) }
    var activeDir by remember { mutableStateOf(initialDir) }
    var requestedTitle by remember { mutableStateOf(initialTitle) }
    var showBooks by remember { mutableStateOf(initialDir.isNullOrBlank()) }
    var showQueue by remember { mutableStateOf(false) }
    var allTracks by remember(refresh) { mutableStateOf(loadPlayerTracks(activity, null)) }
    val books = remember(allTracks) { groupTracksIntoBooks(activity, allTracks) }
    var queueDirs by remember { mutableStateOf(PlayerQueueStore.load(activity)) }
    var tracks by remember(activeDir, allTracks) { mutableStateOf(if (activeDir.isNullOrBlank()) emptyList() else loadPlayerTracks(activity, activeDir)) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sleepUntil by remember { mutableLongStateOf(0L) }
    var showList by remember { mutableStateOf(false) }
    var embeddedCover by remember { mutableStateOf<Bitmap?>(null) }
    val current = tracks.getOrNull(index)
    val activeBook = books.firstOrNull { it.relativeDir == activeDir }
    val siteCover = activeBook?.coverUrl ?: remember(requestedTitle, activeDir, tracks) { siteCoverUrl(activity, requestedTitle ?: activeDir?.trimEnd('/')?.substringAfterLast('/').orEmpty()) }

    fun persistQueue(value: List<String>) { queueDirs = value.distinct(); PlayerQueueStore.save(activity, queueDirs) }
    fun startBook(book: AudioBookGroup, autoPlay: Boolean = false) {
        activeDir = book.relativeDir
        requestedTitle = book.title
        tracks = book.tracks
        showBooks = false
        showQueue = false
        if (autoPlay && controller != null) {
            val c = controller ?: return
            c.stop()
        }
    }
    fun seriesFrom(book: AudioBookGroup): List<AudioBookGroup> {
        val series = book.seriesName ?: return listOf(book)
        return books.filter { it.seriesName == series && it.seriesIndex >= book.seriesIndex }.sortedBy { it.seriesIndex }
    }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { refresh++; allTracks = loadPlayerTracks(activity, null); if (!activeDir.isNullOrBlank()) tracks = loadPlayerTracks(activity, activeDir) }
    }

    DisposableEffect(Unit) {
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        future.addListener({
            runCatching { future.get() }.onSuccess { c ->
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        index = c.currentMediaItemIndex.coerceAtLeast(0)
                        embeddedCover = resolveBookCover(activity, tracks, tracks.getOrNull(index)?.uri)
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        duration = c.duration.coerceAtLeast(0L)
                        if (playbackState == Player.STATE_ENDED && !activeDir.isNullOrBlank()) {
                            val currentQueueIndex = queueDirs.indexOf(activeDir)
                            if (currentQueueIndex >= 0 && currentQueueIndex < queueDirs.lastIndex) {
                                val next = books.firstOrNull { it.relativeDir == queueDirs[currentQueueIndex + 1] }
                                if (next != null) { activeDir = next.relativeDir; requestedTitle = next.title; tracks = next.tracks }
                            }
                        }
                    }
                })
            }
        }, ContextCompat.getMainExecutor(activity))
        onDispose { MediaController.releaseFuture(future) }
    }

    fun loadPlaylist(startIndex: Int, autoPlay: Boolean) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        index = startIndex.coerceIn(0, tracks.lastIndex)
        c.setMediaItems(tracks.map { t -> PlaybackService.mediaItem(PlayerLibraryItem(t.uri.toString(), t.name, t.relativePath, requestedTitle, activeBook?.seriesName)) }, index, 0L)
        c.prepare()
        val saved = PlayerPrefs.position(activity, tracks[index].uri)
        c.seekTo(index, (saved - PlayerPrefs.autoRewindSeconds(activity) * 1000L).coerceAtLeast(0L))
        c.setPlaybackSpeed(speed)
        if (autoPlay) c.play()
        embeddedCover = resolveBookCover(activity, tracks, tracks[index].uri)
    }

    LaunchedEffect(activeDir, controller) {
        if (!activeDir.isNullOrBlank()) {
            tracks = loadPlayerTracks(activity, activeDir)
            index = 0; position = 0; duration = 0
            embeddedCover = resolveBookCover(activity, tracks, tracks.firstOrNull()?.uri)
            if (controller != null && tracks.isNotEmpty()) {
                val shouldAutoPlay = queueDirs.indexOf(activeDir) > 0
                loadPlaylist(0, shouldAutoPlay)
            }
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            delay(500)
            controller?.let { c ->
                position = c.currentPosition.coerceAtLeast(0L); duration = c.duration.coerceAtLeast(0L); playing = c.isPlaying
                tracks.getOrNull(c.currentMediaItemIndex)?.let { PlayerPrefs.savePosition(activity, it.uri, position) }
                if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) { c.pause(); sleepUntil = 0 }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(when { showQueue -> "Черга відтворення"; showBooks -> "Бібліотека"; else -> requestedTitle ?: activeDir?.substringAfterLast('/') ?: "Плеєр" }) },
            navigationIcon = { IconButton(onClick = { when { showQueue -> showQueue = false; showBooks && !activeDir.isNullOrBlank() -> showBooks = false; else -> activity.finish() } }) { Icon(Icons.Filled.ArrowBack, "Назад") } },
            actions = {
                if (showBooks || showQueue) IconButton(onClick = { showQueue = !showQueue; showBooks = !showQueue }) { BadgedBox(badge = { if (queueDirs.isNotEmpty()) Badge { Text(queueDirs.size.toString()) } }) { Icon(Icons.Filled.QueueMusic, "Черга") } }
                if (!showQueue) IconButton(onClick = { refresh++; allTracks = loadPlayerTracks(activity, null); showBooks = true }) { Icon(Icons.Filled.LibraryBooks, "Бібліотека") }
                IconButton(onClick = { activity.startActivity(Intent(activity, PlayerSettingsActivity::class.java)) }) { Icon(Icons.Filled.Settings, "Налаштування плеєра") }
            }
        )
    }) { padding ->
        when {
            showQueue -> QueueScreen(
                modifier = Modifier.padding(padding).fillMaxSize(),
                books = books,
                queueDirs = queueDirs,
                onRemove = { dir -> persistQueue(queueDirs.filterNot { it == dir }) },
                onClear = { persistQueue(emptyList()) },
                onPlay = { dir -> books.firstOrNull { it.relativeDir == dir }?.let { startBook(it) } }
            )
            showBooks -> BookChooser(
                modifier = Modifier.padding(padding).fillMaxSize(),
                books = books,
                queueDirs = queueDirs,
                onRefresh = {
                    if (ContextCompat.checkSelfPermission(activity, audioPermission) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(audioPermission)
                    else { refresh++; allTracks = loadPlayerTracks(activity, null) }
                },
                onSelect = { startBook(it) },
                onQueueBook = { book -> persistQueue(queueDirs + book.relativeDir) },
                onQueueSeries = { book -> persistQueue(queueDirs + seriesFrom(book).map { it.relativeDir }) },
                onPlaySeries = { book -> val sequence = seriesFrom(book); persistQueue(sequence.map { it.relativeDir }); sequence.firstOrNull()?.let { startBook(it) } }
            )
            tracks.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Headphones, null, Modifier.size(64.dp)); Spacer(Modifier.height(12.dp)); Text("Аудіофайли цієї книги не знайдені")
                    Spacer(Modifier.height(8.dp)); Button(onClick = { showBooks = true }) { Text("Вибрати іншу книгу") }
                }
            }
            else -> Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    if (PlayerPrefs.showSleep(activity)) TextButton(onClick = { sleepUntil = if (sleepUntil == 0L) System.currentTimeMillis() + 30 * 60_000L else 0L }) { Icon(Icons.Filled.Bedtime, null); Spacer(Modifier.width(4.dp)); Text(if (sleepUntil > 0) "30 хв" else "Сон") }
                    if (PlayerPrefs.showSpeed(activity)) TextButton(onClick = { speed = when { speed < 1.25f -> 1.25f; speed < 1.5f -> 1.5f; speed < 1.75f -> 1.75f; speed < 2f -> 2f; else -> 1f }; controller?.setPlaybackSpeed(speed) }) { Text(String.format(Locale.US, "%.2gx", speed)) }
                    if (PlayerPrefs.showBookmarks(activity)) IconButton(onClick = { current?.let { activity.getSharedPreferences("bookmarks", Context.MODE_PRIVATE).edit().putLong(it.uri.toString() + "@" + System.currentTimeMillis(), position).apply() } }) { Icon(Icons.Filled.BookmarkAdd, "Закладка") }
                    IconButton(onClick = { showList = !showList }) { Icon(Icons.Filled.PlaylistPlay, "Список файлів") }
                }
                LinearProgressIndicator(progress = { if (duration > 0) position.toFloat() / duration else 0f }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Прочитано ${formatMs(position)}"); Text("${if (duration > 0) ((position * 100 / duration).toInt()) else 0}%"); Text("Залишилось ${formatMs((duration - position).coerceAtLeast(0))}") }
                Spacer(Modifier.height(18.dp))
                if (showList) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(tracks) { i, t ->
                            ListItem(headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(t.relativePath, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingContent = { Icon(if (i == index) Icons.Filled.VolumeUp else Icons.Filled.AudioFile, null) }, modifier = Modifier.clickable { showList = false; loadPlaylist(i, true) })
                            HorizontalDivider()
                        }
                    }
                } else if (embeddedCover != null) Image(embeddedCover!!.asImageBitmap(), current?.name, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                else if (!siteCover.isNullOrBlank()) AsyncImage(model = siteCover, contentDescription = requestedTitle, modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                else Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MenuBook, null, Modifier.size(96.dp)) }
                Spacer(Modifier.height(12.dp)); Text(current?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Slider(value = if (duration > 0) position.toFloat() / duration else 0f, onValueChange = { f -> if (duration > 0) controller?.seekTo((duration * f).toLong()) }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatMs(position)); Text(formatMs(duration)) }
                val seekMs = PlayerPrefs.seekSeconds(activity) * 1000L
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = index > 0) { Icon(Icons.Filled.SkipPrevious, "Попередній файл", Modifier.size(38.dp)) }
                    TextButton(onClick = { controller?.let { it.seekTo((it.currentPosition - seekMs).coerceAtLeast(0L)) } }) { Text("−${seekMs / 1000}с", style = MaterialTheme.typography.titleMedium) }
                    FilledIconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }, modifier = Modifier.size(64.dp)) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Пауза" else "Відтворити", Modifier.size(38.dp)) }
                    TextButton(onClick = { controller?.let { it.seekTo((it.currentPosition + seekMs).coerceAtMost(it.duration.coerceAtLeast(0L))) } }) { Text("+${seekMs / 1000}с", style = MaterialTheme.typography.titleMedium) }
                    IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = index < tracks.lastIndex) { Icon(Icons.Filled.SkipNext, "Наступний файл", Modifier.size(38.dp)) }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun BookChooser(
    modifier: Modifier,
    books: List<AudioBookGroup>,
    queueDirs: List<String>,
    onRefresh: () -> Unit,
    onSelect: (AudioBookGroup) -> Unit,
    onQueueBook: (AudioBookGroup) -> Unit,
    onQueueSeries: (AudioBookGroup) -> Unit,
    onPlaySeries: (AudioBookGroup) -> Unit
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val visible = books.filter { book ->
        val p = bookProgress(context, book)
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.NEW -> book.trackerStatus.equals("NEW", true) && !p.started
            LibraryFilter.STARTED -> p.started && !p.finished
            LibraryFilter.READ -> p.finished || book.trackerStatus.equals("READ", true)
        }
    }
    Column(modifier) {
        ScrollableTabRow(selectedTabIndex = filter.ordinal, edgePadding = 8.dp) {
            listOf("УСІ", "НОВІ", "РОЗПОЧАТІ", "ПРОЧИТАНІ").forEachIndexed { i, title -> Tab(selected = filter.ordinal == i, onClick = { filter = LibraryFilter.entries[i] }, text = { Text(title) }) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${visible.size} книг", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (queueDirs.isNotEmpty()) Text("Черга: ${queueDirs.size}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Оновити") }
            }
        }
        if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (books.isEmpty()) "Завантажені книги не знайдені" else "У цій категорії книг немає") }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(visible, key = { it.relativeDir }) { book -> LibraryBookRow(book, book.relativeDir in queueDirs, onSelect, onQueueBook, onQueueSeries, onPlaySeries) }
        }
    }
}

@Composable
private fun LibraryBookRow(
    book: AudioBookGroup,
    queued: Boolean,
    onSelect: (AudioBookGroup) -> Unit,
    onQueueBook: (AudioBookGroup) -> Unit,
    onQueueSeries: (AudioBookGroup) -> Unit,
    onPlaySeries: (AudioBookGroup) -> Unit
) {
    val context = LocalContext.current
    val localCover = remember(book.relativeDir, book.tracks) { resolveBookCover(context, book.tracks) }
    val progress = remember(book.relativeDir, book.tracks) { bookProgress(context, book) }
    var menu by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().clickable { onSelect(book) }, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(8.dp))) {
                when {
                    localCover != null -> Image(localCover.asImageBitmap(), book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    !book.coverUrl.isNullOrBlank() -> AsyncImage(model = book.coverUrl, contentDescription = book.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MenuBook, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary) }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!book.seriesName.isNullOrBlank()) Text(book.seriesName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${book.tracks.size} файлів", style = MaterialTheme.typography.bodySmall)
                    Text("${(progress.fraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(if (queued) Icons.Filled.QueueMusic else Icons.Filled.MoreVert, "Дії") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Додати книгу в чергу") }, leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) }, onClick = { menu = false; onQueueBook(book) })
                    DropdownMenuItem(text = { Text("Додати серію від цієї книги") }, leadingIcon = { Icon(Icons.Filled.LibraryAdd, null) }, enabled = !book.seriesName.isNullOrBlank(), onClick = { menu = false; onQueueSeries(book) })
                    DropdownMenuItem(text = { Text("Відтворити серію звідси") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, enabled = !book.seriesName.isNullOrBlank(), onClick = { menu = false; onPlaySeries(book) })
                }
            }
        }
    }
}

@Composable
private fun QueueScreen(modifier: Modifier, books: List<AudioBookGroup>, queueDirs: List<String>, onRemove: (String) -> Unit, onClear: () -> Unit, onPlay: (String) -> Unit) {
    val queuedBooks = queueDirs.mapNotNull { dir -> books.firstOrNull { it.relativeDir == dir } }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${queuedBooks.size} книг", style = MaterialTheme.typography.titleMedium)
            if (queuedBooks.isNotEmpty()) TextButton(onClick = onClear) { Icon(Icons.Filled.ClearAll, null); Spacer(Modifier.width(4.dp)); Text("Очистити") }
        }
        if (queuedBooks.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Filled.QueueMusic, null, Modifier.size(72.dp)); Spacer(Modifier.height(12.dp)); Text("Черга порожня"); Text("Додай книгу або серію з бібліотеки", style = MaterialTheme.typography.bodySmall) } }
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(queuedBooks, key = { _, b -> b.relativeDir }) { i, book ->
                ListItem(
                    headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(listOfNotNull(book.seriesName, "${book.tracks.size} файлів").joinToString(" • ")) },
                    leadingContent = { Text("${i + 1}", style = MaterialTheme.typography.titleMedium) },
                    trailingContent = { Row { IconButton(onClick = { onPlay(book.relativeDir) }) { Icon(Icons.Filled.PlayArrow, "Відтворити") }; IconButton(onClick = { onRemove(book.relativeDir) }) { Icon(Icons.Filled.Close, "Прибрати") } } },
                    modifier = Modifier.clickable { onPlay(book.relativeDir) }
                )
                HorizontalDivider()
            }
        }
    }
}

private fun groupTracksIntoBooks(context: Context, tracks: List<AudioTrack>): List<AudioBookGroup> {
    val catalog = trackerCatalog(context)
    return tracks.groupBy { normalizeBookDir(it.relativePath) }.map { (dir, items) ->
        val title = dir.trimEnd('/').substringAfterLast('/').ifBlank { "Книга" }
        val meta = matchTrackerBook(catalog, title)
        AudioBookGroup(title, dir, items.sortedBy { naturalKey(it.name) }, meta?.series, meta?.index ?: Int.MAX_VALUE, meta?.coverUrl, meta?.status)
    }.sortedWith(compareBy<AudioBookGroup> { it.seriesName?.lowercase() ?: "~" }.thenBy { it.seriesIndex }.thenBy { it.title.lowercase() })
}

private fun trackerCatalog(context: Context): List<TrackerBookMeta> {
    val raw = context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", null) ?: return emptyList()
    return runCatching {
        val out = mutableListOf<TrackerBookMeta>(); val root = JSONArray(raw)
        for (i in 0 until root.length()) {
            val series = root.optJSONObject(i) ?: continue
            val seriesName = series.optString("name")
            val books = series.optJSONArray("books") ?: continue
            for (j in 0 until books.length()) {
                val b = books.optJSONObject(j) ?: continue
                out += TrackerBookMeta(b.optString("title"), seriesName, j, b.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" }, b.optString("status").takeIf { it.isNotBlank() })
            }
        }
        out
    }.getOrDefault(emptyList())
}

private fun matchTrackerBook(catalog: List<TrackerBookMeta>, bookTitle: String): TrackerBookMeta? {
    val wanted = normalizeTitle(bookTitle)
    val wantedNumber = bookNumber(bookTitle)
    return catalog.firstOrNull { normalizeTitle(it.title) == wanted }
        ?: catalog.filter { wantedNumber != null && bookNumber(it.title) == wantedNumber }.maxByOrNull { commonTitleScore(wanted, normalizeTitle(it.title)) }
        ?: catalog.maxByOrNull { commonTitleScore(wanted, normalizeTitle(it.title)) }?.takeIf { commonTitleScore(wanted, normalizeTitle(it.title)) >= 2 }
}

private fun commonTitleScore(a: String, b: String): Int {
    val aw = a.split(' ').filter { it.length > 2 }.toSet(); val bw = b.split(' ').filter { it.length > 2 }.toSet()
    return aw.intersect(bw).size
}
private fun bookNumber(value: String): Int? = Regex("(?:^|\\s)(\\d{1,3})(?:\\.|\\s|$)").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun bookProgress(context: Context, book: AudioBookGroup): BookProgress {
    if (book.tracks.isEmpty()) return BookProgress(0f, false, false)
    val positions = book.tracks.map { PlayerPrefs.position(context, it.uri) }
    val lastStarted = positions.indexOfLast { it > 0L }
    if (lastStarted < 0) return BookProgress(if (book.trackerStatus.equals("READ", true)) 1f else 0f, false, book.trackerStatus.equals("READ", true))
    val pos = positions[lastStarted]
    val duration = mediaDuration(context, book.tracks[lastStarted].uri)
    val within = if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val fraction = ((lastStarted + within) / book.tracks.size.toFloat()).coerceIn(0f, 1f)
    val finished = book.trackerStatus.equals("READ", true) || (lastStarted == book.tracks.lastIndex && within >= 0.95f)
    return BookProgress(if (finished) 1f else fraction, true, finished)
}

private fun mediaDuration(context: Context, uri: Uri): Long = runCatching {
    val mmr = MediaMetadataRetriever(); try { mmr.setDataSource(context, uri); mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { mmr.release() }
}.getOrDefault(0L)

private fun normalizeBookDir(path: String): String = path.replace('\\', '/').trimEnd('/')
private fun logicalTrackKey(t: AudioTrack): String = "${normalizeBookDir(t.relativePath).lowercase()}/${t.name.lowercase()}"

private fun loadPlayerTracks(context: Context, relativeDir: String?): List<AudioTrack> {
    val indexed = PlayerLibrary.forPath(context, relativeDir).map { AudioTrack(Uri.parse(it.uri), it.name, it.relativePath) }
    val scanned = scanAudioTracks(context, relativeDir)
    val merged = linkedMapOf<String, AudioTrack>()
    (scanned + indexed).forEach { t -> merged[logicalTrackKey(t)] = t }
    return merged.values.sortedWith(compareBy<AudioTrack> { normalizeBookDir(it.relativePath).lowercase() }.thenBy { naturalKey(it.name) })
}

private fun scanAudioTracks(context: Context, relativeDir: String?): List<AudioTrack> {
    val result = linkedMapOf<String, AudioTrack>()
    fun query(collection: Uri, idColumn: String, nameColumn: String, pathColumn: String) {
        val selection = if (relativeDir.isNullOrBlank()) null else "$pathColumn LIKE ?"
        val args = if (relativeDir.isNullOrBlank()) null else arrayOf("%${relativeDir.replace("%", "\\%")}%")
        runCatching {
            context.contentResolver.query(collection, arrayOf(idColumn, nameColumn, pathColumn), selection, args, "$nameColumn ASC")?.use { c ->
                val idI = c.getColumnIndexOrThrow(idColumn); val nameI = c.getColumnIndexOrThrow(nameColumn); val pathI = c.getColumnIndexOrThrow(pathColumn)
                while (c.moveToNext()) {
                    val name = c.getString(nameI) ?: continue
                    if (name.substringAfterLast('.', "").lowercase() !in setOf("mp3", "m4a", "m4b", "ogg", "opus", "wav", "aac", "flac")) continue
                    val path = c.getString(pathI).orEmpty()
                    if (!path.contains("Audoiboo", true) && relativeDir.isNullOrBlank()) continue
                    val uri = Uri.withAppendedPath(collection, c.getLong(idI).toString())
                    val t = AudioTrack(uri, name, path); result[logicalTrackKey(t)] = t
                }
            }
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH)
    query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH)
    return result.values.toList()
}

private fun resolveBookCover(context: Context, tracks: List<AudioTrack>, preferred: Uri? = null): Bitmap? {
    val ordered = buildList {
        preferred?.let { p -> tracks.firstOrNull { it.uri == p }?.let(::add) }
        tracks.forEach { if (none { x -> x.uri == it.uri }) add(it) }
    }
    for (track in ordered) embeddedCover(context, track.uri)?.let { return it }
    return null
}

private fun embeddedCover(context: Context, uri: Uri): Bitmap? = runCatching {
    val mmr = MediaMetadataRetriever(); try { mmr.setDataSource(context, uri); mmr.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } finally { mmr.release() }
}.getOrNull()

private fun siteCoverUrl(context: Context, bookTitle: String): String? = matchTrackerBook(trackerCatalog(context), bookTitle)?.coverUrl

private fun normalizeTitle(value: String): String = value.lowercase().replace('ё', 'е').replace(Regex("[^a-zа-яіїєґ0-9]+"), " ").trim()
private fun naturalKey(name: String): String = name.lowercase().replace(Regex("(\\d+)")) { it.value.padStart(12, '0') }
private fun formatMs(ms: Long): String { val total = ms / 1000; val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s) }
