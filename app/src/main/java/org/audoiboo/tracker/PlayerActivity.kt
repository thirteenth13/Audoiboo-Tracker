package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

internal data class AudioTrack(val uri: Uri, val name: String, val relativePath: String)

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
    var tracks by remember { mutableStateOf(scanAudioTracks(activity, initialDir)) }
    var index by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var sleepUntil by remember { mutableLongStateOf(0L) }
    var showList by remember { mutableStateOf(false) }
    var cover by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val current = tracks.getOrNull(index)

    fun releasePlayer() { player?.let { p -> current?.let { PlayerPrefs.savePosition(activity, it.uri, p.currentPosition.toLong()) }; runCatching { p.release() } }; player = null; prepared = false; playing = false }
    fun load(i: Int, autoPlay: Boolean = false) {
        if (tracks.isEmpty()) return
        releasePlayer(); index = i.coerceIn(0, tracks.lastIndex); val track = tracks[index]
        position = 0; duration = 0; cover = embeddedCover(activity, track.uri)
        val p = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(activity, track.uri)
            setOnPreparedListener { mp ->
                prepared = true; duration = mp.duration.toLong(); val saved = PlayerPrefs.position(activity, track.uri); val rewind = PlayerPrefs.autoRewindSeconds(activity) * 1000L; val start = max(0L, saved - rewind); if (start in 1 until duration) mp.seekTo(start.toInt()); position = start
                runCatching { mp.playbackParams = mp.playbackParams.setSpeed(speed) }
                if (autoPlay) { mp.start(); playing = true }
            }
            setOnCompletionListener { PlayerPrefs.savePosition(activity, track.uri, 0); if (index < tracks.lastIndex) load(index + 1, true) else playing = false }
            prepareAsync()
        }; player = p
    }

    DisposableEffect(Unit) { onDispose { releasePlayer() } }
    LaunchedEffect(tracks) { if (tracks.isNotEmpty() && player == null) load(0, false) }
    LaunchedEffect(player, playing) { while (true) { delay(500); val p = player; if (p != null && prepared) { position = p.currentPosition.toLong(); if (current != null) PlayerPrefs.savePosition(activity, current.uri, position) }; if (sleepUntil > 0 && System.currentTimeMillis() >= sleepUntil) { p?.pause(); playing = false; sleepUntil = 0 } } }

    Scaffold(topBar = { TopAppBar(title = { Text(initialTitle ?: "Плеєр") }, navigationIcon = { IconButton(onClick = { activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }, actions = { IconButton(onClick = { activity.startActivity(Intent(activity, PlayerSettingsActivity::class.java)) }) { Icon(Icons.Filled.Settings, "Налаштування плеєра") } }) }) { padding ->
        if (tracks.isEmpty()) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Filled.Headphones, null, modifier = Modifier.size(64.dp)); Spacer(Modifier.height(12.dp)); Text("Аудіофайли не знайдені"); TextButton(onClick = { tracks = scanAudioTracks(activity, null) }) { Text("Показати всі завантажені аудіофайли") } } }
        else Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (PlayerPrefs.showSleep(activity)) TextButton(onClick = { sleepUntil = if (sleepUntil == 0L) System.currentTimeMillis() + 30*60_000L else 0L }) { Icon(Icons.Filled.Bedtime, null); Spacer(Modifier.width(4.dp)); Text(if (sleepUntil > 0) "30 хв" else "Сон") }
                if (PlayerPrefs.showSpeed(activity)) TextButton(onClick = { speed = when { speed < 1.25f -> 1.25f; speed < 1.5f -> 1.5f; speed < 2f -> 2f; else -> 1f }; player?.let { runCatching { it.playbackParams = it.playbackParams.setSpeed(speed) } } }) { Text(String.format(Locale.US,"%.2gx",speed)) }
                if (PlayerPrefs.showBookmarks(activity)) IconButton(onClick = { current?.let { activity.getSharedPreferences("bookmarks", Context.MODE_PRIVATE).edit().putLong(it.uri.toString()+"@"+System.currentTimeMillis(), position).apply() } }) { Icon(Icons.Filled.BookmarkAdd, "Закладка") }
                IconButton(onClick = { showList = !showList }) { Icon(Icons.Filled.PlaylistPlay, "Список файлів") }
            }
            LinearProgressIndicator(progress = { if (duration > 0) position.toFloat()/duration else 0f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Прочитано ${formatMs(position)}"); Text("${if(duration>0)((position*100/duration).toInt()) else 0}%"); Text("Залишилось ${formatMs((duration-position).coerceAtLeast(0))}") }
            Spacer(Modifier.height(18.dp))
            if (showList) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) { itemsIndexed(tracks) { i, t -> ListItem(headlineContent = { Text(t.name, maxLines=1, overflow=TextOverflow.Ellipsis) }, supportingContent = { Text(t.relativePath, maxLines=1, overflow=TextOverflow.Ellipsis) }, leadingContent = { Icon(if(i==index) Icons.Filled.VolumeUp else Icons.Filled.AudioFile, null) }, modifier = Modifier.clickable { showList=false; load(i,true) }); HorizontalDivider() } }
            } else {
                val bitmap = cover
                if (bitmap != null) Image(bitmap.asImageBitmap(), current?.name, Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                else Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Filled.MenuBook, null, modifier=Modifier.size(96.dp)) }
            }
            Spacer(Modifier.height(12.dp)); Text(current?.name.orEmpty(), style=MaterialTheme.typography.titleMedium, fontWeight=FontWeight.SemiBold, maxLines=2, overflow=TextOverflow.Ellipsis)
            Slider(value = if(duration>0) position.toFloat()/duration else 0f, onValueChange = { f -> if(duration>0) { position=(duration*f).toLong(); player?.seekTo(position.toInt()) } }, modifier=Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatMs(position)); Text(formatMs(duration)) }
            val seek = PlayerPrefs.seekSeconds(activity)*1000
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if(index>0) load(index-1,true) }) { Icon(Icons.Filled.SkipPrevious,"Попередній",modifier=Modifier.size(38.dp)) }
                TextButton(onClick = { player?.let { val v=(it.currentPosition-seek).coerceAtLeast(0); it.seekTo(v) } }) { Text("−${seek/1000}с", style=MaterialTheme.typography.titleMedium) }
                FilledIconButton(onClick = { player?.let { if(prepared) { if(it.isPlaying){it.pause();playing=false}else{it.start();playing=true} } } }, modifier=Modifier.size(64.dp)) { Icon(if(playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if(playing)"Пауза" else "Відтворити",modifier=Modifier.size(38.dp)) }
                TextButton(onClick = { player?.let { val v=(it.currentPosition+seek).coerceAtMost(it.duration); it.seekTo(v) } }) { Text("+${seek/1000}с", style=MaterialTheme.typography.titleMedium) }
                IconButton(onClick = { if(index<tracks.lastIndex) load(index+1,true) }) { Icon(Icons.Filled.SkipNext,"Наступний",modifier=Modifier.size(38.dp)) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun scanAudioTracks(context: Context, relativeDir: String?): List<AudioTrack> {
    val result = mutableListOf<AudioTrack>(); val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.RELATIVE_PATH)
    val selection = if (relativeDir.isNullOrBlank()) null else "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
    val args = if (relativeDir.isNullOrBlank()) null else arrayOf("%${relativeDir.replace("%","\\%")}%")
    context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, MediaStore.Downloads.DISPLAY_NAME+" ASC")?.use { c ->
        val idI=c.getColumnIndexOrThrow(MediaStore.Downloads._ID); val nameI=c.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME); val pathI=c.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
        while(c.moveToNext()) { val name=c.getString(nameI)?:continue; if(name.substringAfterLast('.',"").lowercase() !in setOf("mp3","m4a","m4b","ogg","opus","wav","aac","flac")) continue; val id=c.getLong(idI); result += AudioTrack(Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,id.toString()),name,c.getString(pathI).orEmpty()) }
    }; return result
}
private fun embeddedCover(context: Context, uri: Uri): android.graphics.Bitmap? = runCatching { val mmr=MediaMetadataRetriever(); mmr.setDataSource(context,uri); val bytes=mmr.embeddedPicture; mmr.release(); bytes?.let{BitmapFactory.decodeByteArray(it,0,it.size)} }.getOrNull()
private fun formatMs(ms: Long): String { val total=ms/1000; val h=total/3600; val m=(total%3600)/60; val s=total%60; return if(h>0) "%d:%02d:%02d".format(h,m,s) else "%d:%02d".format(m,s) }
