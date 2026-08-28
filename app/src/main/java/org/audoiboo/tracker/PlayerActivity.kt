package org.audoiboo.tracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.delay
import java.util.Locale

internal data class AudioTrack(val uri: Uri, val name: String, val relativePath: String)
private data class AudioBookGroup(val title: String, val relativeDir: String, val tracks: List<AudioTrack>)

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
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("seek",seek).putInt("auto_rewind",rewind).putBoolean("resume_call",resume).putBoolean("pause_notifications",notifications).putBoolean("stop_other",other).putInt("force_hours",hours).putBoolean("show_speed",speed).putBoolean("show_sleep",sleep).putBoolean("show_bookmarks",bookmarks).apply()
    }
    fun position(c: Context, uri: Uri) = c.getSharedPreferences("player_positions", Context.MODE_PRIVATE).getLong(uri.toString(), 0L)
    fun savePosition(c: Context, uri: Uri, position: Long) { c.getSharedPreferences("player_positions", Context.MODE_PRIVATE).edit().putLong(uri.toString(), position).apply() }
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
    var allTracks by remember(refresh) { mutableStateOf(loadPlayerTracks(activity, null)) }
    val books = remember(allTracks) { groupTracksIntoBooks(allTracks) }
    var tracks by remember(activeDir, allTracks) { mutableStateOf(if (activeDir.isNullOrBlank()) emptyList() else loadPlayerTracks(activity, activeDir)) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sleepUntil by remember { mutableLongStateOf(0L) }
    var showList by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val current = tracks.getOrNull(index)

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            refresh++
            allTracks = loadPlayerTracks(activity, null)
            if (!activeDir.isNullOrBlank()) tracks = loadPlayerTracks(activity, activeDir)
        }
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
                        cover = tracks.getOrNull(index)?.let { embeddedCover(activity, it.uri) }
                    }
                    override fun onPlaybackStateChanged(playbackState: Int) { duration = c.duration.coerceAtLeast(0L) }
                })
            }
        }, ContextCompat.getMainExecutor(activity))
        onDispose { controller?.let { MediaController.releaseFuture(future) } }
    }

    fun loadPlaylist(startIndex: Int, autoPlay: Boolean) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        index = startIndex.coerceIn(0, tracks.lastIndex)
        val mediaItems = tracks.map { t -> PlaybackService.mediaItem(PlayerLibraryItem(t.uri.toString(), t.name, t.relativePath, requestedTitle)) }
        c.setMediaItems(mediaItems, index, 0L)
        c.prepare()
        val saved = PlayerPrefs.position(activity, tracks[index].uri)
        val rewind = PlayerPrefs.autoRewindSeconds(activity) * 1000L
        c.seekTo(index, (saved - rewind).coerceAtLeast(0L))
        c.setPlaybackSpeed(speed)
        if (autoPlay) c.play()
        cover = embeddedCover(activity, tracks[index].uri)
    }

    LaunchedEffect(activeDir, controller) {
        if (!activeDir.isNullOrBlank()) {
            tracks = loadPlayerTracks(activity, activeDir)
            index = 0
            position = 0
            duration = 0
            cover = tracks.firstOrNull()?.let { embeddedCover(activity, it.uri) }
            if (controller != null && tracks.isNotEmpty()) loadPlaylist(0, false)
        }
    }
    LaunchedEffect(controller) {
        while (true) {
            delay(500)
            controller?.let { c ->
                position = c.currentPosition.coerceAtLeast(0L)
                duration = c.duration.coerceAtLeast(0L)
                playing = c.isPlaying
                tracks.getOrNull(c.currentMediaItemIndex)?.let { PlayerPrefs.savePosition(activity, it.uri, position) }
                if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) { c.pause(); sleepUntil = 0 }
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (showBooks) "Вибір книги" else requestedTitle ?: activeDir?.substringAfterLast('/') ?: "Плеєр") },
            navigationIcon = { IconButton(onClick = { if (showBooks && !activeDir.isNullOrBlank()) showBooks = false else activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } },
            actions = {
                IconButton(onClick = { refresh++; allTracks = loadPlayerTracks(activity, null); showBooks = true }) { Icon(Icons.Filled.LibraryBooks, "Вибрати книгу") }
                IconButton(onClick = { activity.startActivity(Intent(activity, PlayerSettingsActivity::class.java)) }) { Icon(Icons.Filled.Settings, "Налаштування плеєра") }
            }
        )
    }) { padding ->
        if (showBooks) {
            BookChooser(
                modifier = Modifier.padding(padding).fillMaxSize(),
                books = books,
                onRefresh = {
                    if (ContextCompat.checkSelfPermission(activity, audioPermission) != PackageManager.PERMISSION_GRANTED) permissionLauncher.launch(audioPermission)
                    else { refresh++; allTracks = loadPlayerTracks(activity, null) }
                },
                onSelect = { book ->
                    activeDir = book.relativeDir
                    requestedTitle = book.title
                    tracks = book.tracks
                    showBooks = false
                }
            )
        } else if (tracks.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Headphones, null, modifier = Modifier.size(64.dp)); Spacer(Modifier.height(12.dp))
                    Text("Аудіофайли цієї книги не знайдені")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showBooks = true }) { Text("Вибрати іншу книгу") }
                }
            }
        } else Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (PlayerPrefs.showSleep(activity)) TextButton(onClick = { sleepUntil = if (sleepUntil == 0L) System.currentTimeMillis() + 30*60_000L else 0L }) { Icon(Icons.Filled.Bedtime, null); Spacer(Modifier.width(4.dp)); Text(if (sleepUntil > 0) "30 хв" else "Сон") }
                if (PlayerPrefs.showSpeed(activity)) TextButton(onClick = { speed = when { speed < 1.25f -> 1.25f; speed < 1.5f -> 1.5f; speed < 1.75f -> 1.75f; speed < 2f -> 2f; else -> 1f }; controller?.setPlaybackSpeed(speed) }) { Text(String.format(Locale.US,"%.2gx",speed)) }
                if (PlayerPrefs.showBookmarks(activity)) IconButton(onClick = { current?.let { activity.getSharedPreferences("bookmarks", Context.MODE_PRIVATE).edit().putLong(it.uri.toString()+"@"+System.currentTimeMillis(), position).apply() } }) { Icon(Icons.Filled.BookmarkAdd, "Закладка") }
                IconButton(onClick = { showList = !showList }) { Icon(Icons.Filled.PlaylistPlay, "Список файлів") }
            }
            LinearProgressIndicator(progress = { if (duration > 0) position.toFloat()/duration else 0f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Прочитано ${formatMs(position)}"); Text("${if(duration>0)((position*100/duration).toInt()) else 0}%"); Text("Залишилось ${formatMs((duration-position).coerceAtLeast(0))}") }
            Spacer(Modifier.height(18.dp))
            if (showList) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) { itemsIndexed(tracks) { i, t -> ListItem(headlineContent = { Text(t.name, maxLines=1, overflow=TextOverflow.Ellipsis) }, supportingContent = { Text(t.relativePath, maxLines=1, overflow=TextOverflow.Ellipsis) }, leadingContent = { Icon(if(i==index) Icons.Filled.VolumeUp else Icons.Filled.AudioFile, null) }, modifier = Modifier.clickable { showList=false; loadPlaylist(i,true) }); HorizontalDivider() } }
            } else {
                val bitmap = cover
                if (bitmap != null) Image(bitmap.asImageBitmap(), current?.name, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                else Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MenuBook, null, modifier=Modifier.size(96.dp)) }
            }
            Spacer(Modifier.height(12.dp)); Text(current?.name.orEmpty(), style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold, maxLines=2, overflow=TextOverflow.Ellipsis)
            Slider(value = if(duration>0) position.toFloat()/duration else 0f, onValueChange = { f -> if(duration>0) controller?.seekTo((duration*f).toLong()) }, modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatMs(position)); Text(formatMs(duration)) }
            val seekMs = PlayerPrefs.seekSeconds(activity)*1000L
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { controller?.seekToPreviousMediaItem() }, enabled = index > 0) { Icon(Icons.Filled.SkipPrevious,"Попередній файл",modifier=Modifier.size(38.dp)) }
                TextButton(onClick = { controller?.let { it.seekTo((it.currentPosition-seekMs).coerceAtLeast(0L)) } }) { Text("−${seekMs/1000}с", style=MaterialTheme.typography.titleMedium) }
                FilledIconButton(onClick = { controller?.let { if(it.isPlaying) it.pause() else it.play() } }, modifier=Modifier.size(64.dp)) { Icon(if(playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(playing)"Пауза" else "Відтворити",modifier=Modifier.size(38.dp)) }
                TextButton(onClick = { controller?.let { it.seekTo((it.currentPosition+seekMs).coerceAtMost(it.duration.coerceAtLeast(0L))) } }) { Text("+${seekMs/1000}с", style=MaterialTheme.typography.titleMedium) }
                IconButton(onClick = { controller?.seekToNextMediaItem() }, enabled = index < tracks.lastIndex) { Icon(Icons.Filled.SkipNext,"Наступний файл",modifier=Modifier.size(38.dp)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun BookChooser(modifier: Modifier, books: List<AudioBookGroup>, onRefresh: () -> Unit, onSelect: (AudioBookGroup) -> Unit) {
    val context = LocalContext.current
    if (books.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.LibraryBooks, null, modifier = Modifier.size(72.dp))
                Spacer(Modifier.height(12.dp))
                Text("Завантажені книги не знайдені")
                Text("Пошук виконується в Downloads/Audoiboo", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onRefresh) { Text("Оновити бібліотеку") }
            }
        }
        return
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${books.size} книг", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, null); Spacer(Modifier.width(4.dp)); Text("Оновити") }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(books, key = { it.relativeDir }) { book ->
                val bookCover = remember(book.relativeDir, book.tracks) { book.tracks.firstOrNull()?.let { embeddedCover(context, it.uri) } }
                ElevatedCard(Modifier.fillMaxWidth().clickable { onSelect(book) }, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (bookCover != null) {
                            Image(bookCover.asImageBitmap(), book.title, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        } else {
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.MenuBook, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${book.tracks.size} аудіофайлів", style = MaterialTheme.typography.bodySmall)
                            Text(book.relativeDir, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            }
        }
    }
}

private fun groupTracksIntoBooks(tracks: List<AudioTrack>): List<AudioBookGroup> {
    return tracks.groupBy { normalizeBookDir(it.relativePath) }
        .map { (dir, items) -> AudioBookGroup(dir.trimEnd('/').substringAfterLast('/').ifBlank { "Книга" }, dir, items.sortedBy { naturalKey(it.name) }) }
        .sortedBy { it.title.lowercase() }
}

private fun normalizeBookDir(path: String): String = path.trimEnd('/')

private fun loadPlayerTracks(context: Context, relativeDir: String?): List<AudioTrack> {
    val indexed = PlayerLibrary.forPath(context, relativeDir).map { AudioTrack(Uri.parse(it.uri), it.name, it.relativePath) }
    val scanned = scanAudioTracks(context, relativeDir)
    return (indexed + scanned).distinctBy { it.uri.toString() }.sortedWith(compareBy<AudioTrack> { it.relativePath }.thenBy { naturalKey(it.name) })
}

private fun scanAudioTracks(context: Context, relativeDir: String?): List<AudioTrack> {
    val result = linkedMapOf<String, AudioTrack>()
    fun query(collection: Uri, idColumn: String, nameColumn: String, pathColumn: String) {
        val projection = arrayOf(idColumn, nameColumn, pathColumn)
        val selection = if (relativeDir.isNullOrBlank()) null else "$pathColumn LIKE ?"
        val args = if (relativeDir.isNullOrBlank()) null else arrayOf("%${relativeDir.replace("%","\\%")}%")
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, "$nameColumn ASC")?.use { c ->
                val idI=c.getColumnIndexOrThrow(idColumn); val nameI=c.getColumnIndexOrThrow(nameColumn); val pathI=c.getColumnIndexOrThrow(pathColumn)
                while(c.moveToNext()) {
                    val name=c.getString(nameI)?:continue
                    if(name.substringAfterLast('.',"").lowercase() !in setOf("mp3","m4a","m4b","ogg","opus","wav","aac","flac")) continue
                    val id=c.getLong(idI); val uri=Uri.withAppendedPath(collection,id.toString()); val path=c.getString(pathI).orEmpty()
                    if (!path.contains("Audoiboo", true) && relativeDir.isNullOrBlank()) continue
                    result[uri.toString()] = AudioTrack(uri,name,path)
                }
            }
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH)
    query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH)
    return result.values.toList()
}

private fun naturalKey(name: String): String = name.lowercase().replace(Regex("(\\d+)")) { it.value.padStart(12,'0') }
private fun embeddedCover(context: Context, uri: Uri): android.graphics.Bitmap? = runCatching { val mmr=MediaMetadataRetriever(); mmr.setDataSource(context,uri); val bytes=mmr.embeddedPicture; mmr.release(); bytes?.let{BitmapFactory.decodeByteArray(it,0,it.size)} }.getOrNull()
private fun formatMs(ms: Long): String { val total=ms/1000; val h=total/3600; val m=(total%3600)/60; val s=total%60; return if(h>0) "%d:%02d:%02d".format(h,m,s) else "%d:%02d".format(m,s) }
