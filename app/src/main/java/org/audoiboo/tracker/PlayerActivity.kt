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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
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
private data class BookProgress(val fraction: Float, val started: Boolean, val finished: Boolean, val currentTrack: Int)
private enum class LibraryFilter { ALL, NEW, STARTED, READ }
private enum class LibrarySort { SERIES, RECENT, TITLE, PROGRESS }
private enum class PlayerPage { LIBRARY, QUEUE, HISTORY, STATS, SERIES, PLAYER }

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
    fun position(c: Context, uri: Uri) = TrackPositionStore.position(c, uri)
    fun savePosition(c: Context, uri: Uri, position: Long) { TrackPositionStore.save(c, uri, position) }
}

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); TrackPositionStore.initialize(this); PlaybackQueueStore.initialize(this); PlayerStateStore.initialize(this); setContent { AudoibooTheme(this) { PlayerScreen(this, intent.getStringExtra("relativeDir"), intent.getStringExtra("title")) } } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerScreen(activity: ComponentActivity, initialDir: String?, initialTitle: String?) {
    val initialSnapshot = remember { PlayerExtras.snapshot(activity) }
    val trackerRevision by RoomTrackerCatalog.revision.collectAsState()
    val trackPositions by TrackPositionStore.observe().collectAsState()
    val roomQueue by PlaybackQueueStore.observe().collectAsState()
    val brokenUris by PlayerStateStore.observeBroken().collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var allTracks by remember(refresh) { mutableStateOf(loadPlayerTracks(activity, null)) }
    val books = remember(allTracks, refresh, trackerRevision, trackPositions, brokenUris) { groupTracksIntoBooks(activity, allTracks) }
    var queueDirs by remember { mutableStateOf(initialSnapshot?.queue?.takeIf { it.isNotEmpty() } ?: PlaybackQueueStore.current(activity)) }
    var activeDir by remember { mutableStateOf(initialDir ?: initialSnapshot?.dir ?: PlayerExtras.resume(activity)?.dir) }
    var requestedTitle by remember { mutableStateOf(initialTitle ?: initialSnapshot?.title ?: PlayerExtras.resume(activity)?.title) }
    var page by remember { mutableStateOf(if (initialDir.isNullOrBlank()) PlayerPage.LIBRARY else PlayerPage.PLAYER) }
    var selectedSeries by remember { mutableStateOf<String?>(null) }
    var tracks by remember(activeDir, allTracks) { mutableStateOf(if (activeDir.isNullOrBlank()) emptyList() else loadPlayerTracks(activity, activeDir)) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(PlayerExtras.speedFor(activity, activeDir)) }
    var sleepUntil by remember { mutableLongStateOf(0L) }
    var sleepAtEndOfTrack by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var embeddedCover by remember { mutableStateOf<Bitmap?>(null) }
    var bookmarkDialog by remember { mutableStateOf(false) }
    var bookmarkNote by remember { mutableStateOf("") }
    var showBookmarks by remember { mutableStateOf(false) }
    var lastTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastPlaybackError by remember { mutableStateOf<String?>(null) }
    val current = tracks.getOrNull(index)
    val activeBook = books.firstOrNull { it.relativeDir == activeDir }
    val siteCover = activeBook?.coverUrl ?: siteCoverUrl(activity, requestedTitle.orEmpty())

    LaunchedEffect(roomQueue) { if (queueDirs != roomQueue) queueDirs = roomQueue }

    fun currentBrokenIndices(extraBroken: String? = null): Set<Int> {
        val state = if (extraBroken.isNullOrBlank()) PlayerExtras.brokenUris(activity) else PlayerExtras.brokenUris(activity) + extraBroken
        return PlayerLogic.brokenIndices(tracks.map { it.uri.toString() }, state)
    }
    fun persistQueue(v: List<String>) { queueDirs = v.distinct(); PlaybackQueueStore.save(activity, queueDirs) }
    fun persistSnapshot() {
        val dir = activeDir ?: return
        PlayerExtras.saveSnapshot(activity, dir, requestedTitle ?: dir, tracks.getOrNull(index)?.uri, index, position, queueDirs)
    }
    fun startBook(book: AudioBookGroup, autoPlay: Boolean = false) {
        activeDir=book.relativeDir; requestedTitle=book.title; tracks=book.tracks; page=PlayerPage.PLAYER
        speed = PlayerExtras.speedFor(activity, book.relativeDir)
        PlayerExtras.rememberBook(activity,book.relativeDir,book.title,book.tracks.firstOrNull()?.uri)
        if (autoPlay) controller?.stop()
    }
    fun seriesFrom(book: AudioBookGroup): List<AudioBookGroup> { val s=book.seriesName?:return listOf(book); return books.filter{it.seriesName==s && it.seriesIndex>=book.seriesIndex}.sortedBy{it.seriesIndex} }
    fun refreshLibrary(clean:Boolean=false) {
        if (clean) PlayerExtras.clearBroken(activity)
        val scanned=scanAudioTracks(activity,null)
        if(clean) PlayerLibrary.replaceAll(activity,scanned.map{PlayerLibraryItem(it.uri.toString(),it.name,it.relativePath)})
        refresh++; allTracks=loadPlayerTracks(activity,null); syncTrackerStatuses(activity,groupTracksIntoBooks(activity,allTracks))
    }

    val audioPermission = if (Build.VERSION.SDK_INT>=33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it)refreshLibrary(false)}

    DisposableEffect(Unit) {
        val token=SessionToken(activity,ComponentName(activity,PlaybackService::class.java)); val future=MediaController.Builder(activity,token).buildAsync()
        future.addListener({ runCatching{future.get()}.onSuccess{c->controller=c;c.addListener(object:Player.Listener{
            override fun onIsPlayingChanged(v:Boolean){playing=v;if(!v)persistSnapshot()}
            override fun onMediaItemTransition(item:MediaItem?,reason:Int){
                val currentIndex=c.currentMediaItemIndex.coerceAtLeast(0)
                if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO&&sleepAtEndOfTrack){c.pause();sleepAtEndOfTrack=false}
                val knownBroken=currentBrokenIndices()
                if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO&&currentIndex in knownBroken){
                    val next=PlayerLogic.nextPlayableIndex(tracks.size,currentIndex,knownBroken)
                    if(next!=null){c.seekTo(next,0L);c.prepare();c.play();return}else c.pause()
                }
                index=currentIndex;position=c.currentPosition.coerceAtLeast(0);embeddedCover=resolveBookCover(activity,tracks,tracks.getOrNull(index)?.uri);persistSnapshot()
            }
            override fun onPlayerError(error: PlaybackException) {
                val failedIndex=c.currentMediaItemIndex.coerceAtLeast(0)
                val failed=tracks.getOrNull(failedIndex)
                if(failed!=null)PlayerExtras.markBroken(activity,failed.uri)
                lastPlaybackError=failed?.let{"Пропущено пошкоджений файл: ${it.name}"}?:error.message
                val knownBroken=currentBrokenIndices(failed?.uri?.toString())
                val next=PlayerLogic.nextPlayableIndex(tracks.size,failedIndex,knownBroken)
                if(next!=null){c.seekTo(next,0L);c.prepare();c.play()}else c.pause()
            }
            override fun onPlaybackStateChanged(state:Int){duration=c.duration.coerceAtLeast(0L);if(state==Player.STATE_ENDED&&!activeDir.isNullOrBlank()){persistSnapshot();val qi=queueDirs.indexOf(activeDir);if(qi>=0&&qi<queueDirs.lastIndex){books.firstOrNull{it.relativeDir==queueDirs[qi+1]}?.let{startBook(it,true)}}}}
        })}},ContextCompat.getMainExecutor(activity)); onDispose{persistSnapshot();MediaController.releaseFuture(future)}
    }

    fun loadPlaylist(start:Int,autoPlay:Boolean){
        val c=controller?:return;if(tracks.isEmpty())return
        val broken=currentBrokenIndices();val requested=start.coerceIn(0,tracks.lastIndex);val playable=PlayerLogic.resumePlayableIndex(tracks.size,requested,broken)
        if(playable==null){lastPlaybackError="У цій книзі немає доступних аудіофайлів";c.stop();return}
        index=playable
        c.setMediaItems(tracks.map{t->PlaybackService.mediaItem(PlayerLibraryItem(t.uri.toString(),t.name,t.relativePath,requestedTitle,activeBook?.seriesName))},index,0L);c.prepare()
        val snapshot=PlayerExtras.snapshot(activity)
        val saved=PlayerPrefs.position(activity,tracks[index].uri)
        val stored=snapshot?.takeIf{it.dir==activeDir&&it.uri==tracks[index].uri.toString()}?.let{maxOf(it.positionMs,saved)}?:saved
        val resume=PlayerExtras.resume(activity);val smart=if(resume?.uri==tracks[index].uri.toString())PlayerExtras.smartRewindMs(resume.at)else 0L
        val rewind=maxOf(PlayerPrefs.autoRewindSeconds(activity)*1000L,smart)
        val knownDuration=mediaDuration(activity,tracks[index].uri)
        c.seekTo(index,PlayerLogic.safeResumePosition(stored,knownDuration,rewind));c.setPlaybackSpeed(speed);if(autoPlay)c.play();embeddedCover=resolveBookCover(activity,tracks,tracks[index].uri)
    }

    LaunchedEffect(activeDir,controller){if(!activeDir.isNullOrBlank()){
        tracks=loadPlayerTracks(activity,activeDir);speed=PlayerExtras.speedFor(activity,activeDir)
        val snap=PlayerExtras.snapshot(activity);val matchingSnap=snap?.takeIf{it.dir==activeDir}
        val savedUri=matchingSnap?.uri?:PlayerExtras.resume(activity)?.uri
        val requested=tracks.indexOfFirst{it.uri.toString()==savedUri}.takeIf{it>=0}?:matchingSnap?.fileIndex?.takeIf{it in tracks.indices}?:0
        val playable=PlayerLogic.resumePlayableIndex(tracks.size,requested,currentBrokenIndices())
        if(playable==null&&tracks.isNotEmpty()){index=0;position=0;lastPlaybackError="У цій книзі немає доступних аудіофайлів"}else index=playable?:0
        position=if(index==requested)matchingSnap?.positionMs?:0 else 0;duration=0;embeddedCover=resolveBookCover(activity,tracks,tracks.getOrNull(index)?.uri)
        if(controller!=null&&tracks.isNotEmpty()&&playable!=null)loadPlaylist(index,false)
    }}
    LaunchedEffect(brokenUris){
        if(tracks.isNotEmpty()){
            val broken=PlayerLogic.brokenIndices(tracks.map{it.uri.toString()},brokenUris)
            if(index in broken){
                val target=PlayerLogic.resumePlayableIndex(tracks.size,index,broken)
                if(target!=null&&target!=index){index=target;position=0;controller?.let{c->c.seekTo(target,0L);c.prepare();if(playing)c.play()}}
                else if(target==null){lastPlaybackError="У цій книзі немає доступних аудіофайлів";controller?.pause()}
            }
        }
    }
    LaunchedEffect(controller){while(true){delay(1000);controller?.let{c->val now=System.currentTimeMillis();if(c.isPlaying)PlayerExtras.addListened(activity,(now-lastTick).coerceAtMost(2000));lastTick=now;position=c.currentPosition.coerceAtLeast(0);duration=c.duration.coerceAtLeast(0);playing=c.isPlaying;tracks.getOrNull(c.currentMediaItemIndex)?.let{t->PlayerPrefs.savePosition(activity,t.uri,position);if(!activeDir.isNullOrBlank()){PlayerExtras.rememberBook(activity,activeDir!!,requestedTitle?:activeDir!!,t.uri);PlayerExtras.saveSnapshot(activity,activeDir!!,requestedTitle?:activeDir!!,t.uri,c.currentMediaItemIndex.coerceAtLeast(0),position,queueDirs)}};if(sleepUntil>0){val remaining=sleepUntil-now;if(remaining<=0){c.pause();c.volume=1f;sleepUntil=0}else if(remaining<=60_000){c.volume=(remaining/60_000f).coerceIn(.05f,1f)}else c.volume=1f}else if(c.volume!=1f)c.volume=1f}}}

    Scaffold(topBar={TopAppBar(title={Text(when(page){PlayerPage.LIBRARY->"Бібліотека";PlayerPage.QUEUE->"Черга";PlayerPage.HISTORY->"Історія";PlayerPage.STATS->"Статистика";PlayerPage.SERIES->selectedSeries?:"Серія";PlayerPage.PLAYER->requestedTitle?:"Плеєр"})},navigationIcon={IconButton(onClick={persistSnapshot();if(page==PlayerPage.PLAYER&&initialDir!=null)activity.finish()else if(page==PlayerPage.LIBRARY)activity.finish()else page=PlayerPage.LIBRARY}){Icon(Icons.Filled.ArrowBack,"Назад")}},actions={
        if(page==PlayerPage.LIBRARY){IconButton(onClick={page=PlayerPage.HISTORY}){Icon(Icons.Filled.History,"Історія")};IconButton(onClick={page=PlayerPage.STATS}){Icon(Icons.Filled.BarChart,"Статистика")}}
        IconButton(onClick={page=PlayerPage.QUEUE}){BadgedBox(badge={if(queueDirs.isNotEmpty())Badge{Text(queueDirs.size.toString())}}){Icon(Icons.Filled.QueueMusic,"Черга")}}
        IconButton(onClick={activity.startActivity(Intent(activity,PlayerSettingsActivity::class.java))}){Icon(Icons.Filled.Settings,"Налаштування")}
    })}){padding->
        when(page){
            PlayerPage.LIBRARY->LibraryScreen(Modifier.padding(padding).fillMaxSize(),activity,books,queueDirs,onRefresh={if(ContextCompat.checkSelfPermission(activity,audioPermission)!=PackageManager.PERMISSION_GRANTED)permissionLauncher.launch(audioPermission)else refreshLibrary(false)},onClean={refreshLibrary(true)},onSelect={startBook(it)},onSeries={selectedSeries=it;page=PlayerPage.SERIES},onQueue={b->persistQueue(queueDirs+b.relativeDir)},onNext={b->persistQueue(PlayerQueueActions.playNext(queueDirs,activeDir,b.relativeDir))},onQueueSeries={b->persistQueue(queueDirs+seriesFrom(b).map{it.relativeDir})})
            PlayerPage.QUEUE->QueueScreen(Modifier.padding(padding).fillMaxSize(),books,queueDirs,onPlay={d->books.firstOrNull{it.relativeDir==d}?.let{startBook(it,true)}},onRemove={d->persistQueue(queueDirs.filterNot{it==d})},onMove={from,to->persistQueue(PlayerQueueActions.move(queueDirs,from,to))},onClear={persistQueue(emptyList())})
            PlayerPage.HISTORY->HistoryScreen(Modifier.padding(padding).fillMaxSize(),activity,books,onPlay={startBook(it)})
            PlayerPage.STATS->StatsScreen(Modifier.padding(padding).fillMaxSize(),activity,books)
            PlayerPage.SERIES->SeriesScreen(Modifier.padding(padding).fillMaxSize(),activity,books.filter{it.seriesName==selectedSeries},onPlay={startBook(it)},onQueueAll={persistQueue(queueDirs+it.map{b->b.relativeDir})})
            PlayerPage.PLAYER->PlayerPane(Modifier.padding(padding).fillMaxSize(),activity,tracks,index,position,duration,playing,speed,sleepUntil,sleepAtEndOfTrack,embeddedCover,siteCover,showList,showBookmarks,lastPlaybackError,controller,brokenUris,
                onSpeed={speed=it;activeDir?.let{d->PlayerExtras.setSpeed(activity,d,it)};controller?.setPlaybackSpeed(it)},onSleep={mins->sleepUntil=if(mins==0)0 else System.currentTimeMillis()+mins*60_000L;sleepAtEndOfTrack=false;if(mins==0)controller?.volume=1f},onSleepTrack={sleepAtEndOfTrack=!sleepAtEndOfTrack;sleepUntil=0;controller?.volume=1f},onToggleList={showList=!showList},onToggleBookmarks={showBookmarks=!showBookmarks},onBookmark={bookmarkDialog=true},onTrack={loadPlaylist(it,true)},onPrevious={PlayerLogic.previousPlayableIndex(tracks.size,index,currentBrokenIndices())?.let{target->controller?.seekTo(target,0L);controller?.play()}},onNext={PlayerLogic.nextPlayableIndex(tracks.size,index,currentBrokenIndices())?.let{target->controller?.seekTo(target,0L);controller?.play()}})
        }
    }

    if(bookmarkDialog)AlertDialog(onDismissRequest={bookmarkDialog=false},title={Text("Нова закладка")},text={OutlinedTextField(bookmarkNote,{bookmarkNote=it},label={Text("Примітка")})},confirmButton={TextButton(onClick={current?.let{PlayerExtras.addBookmark(activity,it.uri,position,bookmarkNote)};bookmarkNote="";bookmarkDialog=false}){Text("Зберегти")}},dismissButton={TextButton(onClick={bookmarkDialog=false}){Text("Скасувати")}})
}

@Composable private fun LibraryScreen(modifier:Modifier,context:Context,books:List<AudioBookGroup>,queue:List<String>,onRefresh:()->Unit,onClean:()->Unit,onSelect:(AudioBookGroup)->Unit,onSeries:(String)->Unit,onQueue:(AudioBookGroup)->Unit,onNext:(AudioBookGroup)->Unit,onQueueSeries:(AudioBookGroup)->Unit){
    var filter by remember{mutableStateOf(LibraryFilter.ALL)};var query by remember{mutableStateOf("")};var sort by remember{mutableStateOf(LibrarySort.SERIES)};var sortMenu by remember{mutableStateOf(false)}
    val resume=PlayerExtras.resume(context);val resumeBook=books.firstOrNull{it.relativeDir==resume?.dir}
    val visible=books.filter{b->val p=bookProgress(context,b);val f=when(filter){LibraryFilter.ALL->true;LibraryFilter.NEW->!p.started&&!p.finished;LibraryFilter.STARTED->p.started&&!p.finished;LibraryFilter.READ->p.finished};f&&(query.isBlank()||b.title.contains(query,true)||b.seriesName?.contains(query,true)==true||PlayerExtras.tags(context,b.relativeDir).any{it.contains(query,true)})}.let{list->when(sort){LibrarySort.SERIES->list.sortedWith(compareBy<AudioBookGroup>{it.seriesName?:"~"}.thenBy{it.seriesIndex});LibrarySort.RECENT->list.sortedByDescending{b->PlayerExtras.history(context).firstOrNull{it.dir==b.relativeDir}?.at?:0};LibrarySort.TITLE->list.sortedBy{it.title.lowercase()};LibrarySort.PROGRESS->list.sortedByDescending{bookProgress(context,it).fraction}}}
    Column(modifier){
        if(resumeBook!=null){ElevatedCard(Modifier.padding(10.dp).fillMaxWidth().clickable{onSelect(resumeBook)}){Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically){Cover(resumeBook,72.dp);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text("Продовжити слухати",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold);Text(resumeBook.title,maxLines=2);val p=bookProgress(context,resumeBook);Text("Файл ${p.currentTrack.coerceAtLeast(1)} із ${resumeBook.tracks.size} • ${(p.fraction*100).toInt()}%",style=MaterialTheme.typography.bodySmall)};Icon(Icons.Filled.PlayCircle,null,Modifier.size(42.dp))}}}
        ScrollableTabRow(filter.ordinal,edgePadding=4.dp){listOf("УСІ","НОВІ","РОЗПОЧАТІ","ПРОЧИТАНІ").forEachIndexed{i,t->Tab(filter.ordinal==i,{filter=LibraryFilter.entries[i]},text={Text(t)})}}
        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){OutlinedTextField(query,{query=it},Modifier.weight(1f),leadingIcon={Icon(Icons.Filled.Search,null)},placeholder={Text("Пошук назви, серії або тегу")},singleLine=true);Box{IconButton({sortMenu=true}){Icon(Icons.Filled.Sort,"Сортування")};DropdownMenu(sortMenu,{sortMenu=false}){LibrarySort.entries.forEach{s->DropdownMenuItem({Text(when(s){LibrarySort.SERIES->"За серією";LibrarySort.RECENT->"Останні";LibrarySort.TITLE->"За назвою";LibrarySort.PROGRESS->"За прогресом"})},{sort=s;sortMenu=false})}}};IconButton(onRefresh){Icon(Icons.Filled.Refresh,"Оновити")};IconButton(onClean){Icon(Icons.Filled.CleaningServices,"Пересканувати")}}
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){items(visible,key={it.relativeDir}){b->LibraryRow(context,b,b.relativeDir in queue,onSelect,onSeries,onQueue,onNext,onQueueSeries)}}
    }
}

@Composable private fun LibraryRow(context:Context,b:AudioBookGroup,queued:Boolean,onSelect:(AudioBookGroup)->Unit,onSeries:(String)->Unit,onQueue:(AudioBookGroup)->Unit,onNext:(AudioBookGroup)->Unit,onQueueSeries:(AudioBookGroup)->Unit){var menu by remember{mutableStateOf(false)};val p=bookProgress(context,b);ElevatedCard(Modifier.fillMaxWidth().clickable{onSelect(b)}){Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Box{Cover(b,92.dp);if(p.finished)AssistChip({}, {Text("✓")},Modifier.align(Alignment.TopEnd))else if(p.started)Surface(Modifier.align(Alignment.BottomEnd),shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.primary){Text("${(p.fraction*100).toInt()}%",Modifier.padding(horizontal=5.dp),color=MaterialTheme.colorScheme.onPrimary)}};Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(b.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);if(!b.seriesName.isNullOrBlank())TextButton(onClick={onSeries(b.seriesName) },contentPadding=PaddingValues(0.dp)){Text(b.seriesName,maxLines=1)};LinearProgressIndicator({p.fraction},Modifier.fillMaxWidth());Text("Файл ${p.currentTrack.coerceAtLeast(1)} із ${b.tracks.size} • ${b.tracks.size} файлів",style=MaterialTheme.typography.bodySmall);val tags=PlayerExtras.tags(context,b.relativeDir);if(tags.isNotEmpty())Text(tags.joinToString(" • "),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)};Box{IconButton({menu=true}){Icon(if(queued)Icons.Filled.QueueMusic else Icons.Filled.MoreVert,"Дії")};DropdownMenu(menu,{menu=false}){DropdownMenuItem({Text("Відтворити наступною")},{menu=false;onNext(b)},leadingIcon={Icon(Icons.Filled.SkipNext,null)});DropdownMenuItem({Text("Додати в чергу")},{menu=false;onQueue(b)},leadingIcon={Icon(Icons.Filled.PlaylistAdd,null)});DropdownMenuItem({Text("Додати серію звідси")},{menu=false;onQueueSeries(b)},enabled=!b.seriesName.isNullOrBlank(),leadingIcon={Icon(Icons.Filled.LibraryAdd,null)})}}}}}

@Composable private fun Cover(b:AudioBookGroup,size:androidx.compose.ui.unit.Dp){val c=LocalContext.current;val local=remember(b.relativeDir){resolveBookCover(c,b.tracks)};Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){if(local!=null)Image(local.asImageBitmap(),b.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else if(!b.coverUrl.isNullOrBlank())AsyncImage(b.coverUrl,b.title,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)else Icon(Icons.Filled.MenuBook,null,Modifier.size(size/2))}}

@Composable private fun QueueScreen(modifier:Modifier,books:List<AudioBookGroup>,dirs:List<String>,onPlay:(String)->Unit,onRemove:(String)->Unit,onMove:(Int,Int)->Unit,onClear:()->Unit){val q=dirs.mapNotNull{d->books.firstOrNull{it.relativeDir==d}};Column(modifier){Row(Modifier.padding(12.dp).fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("${q.size} книг",style=MaterialTheme.typography.titleMedium);if(q.isNotEmpty())TextButton(onClick=onClear){Text("Очистити")}};LazyColumn{itemsIndexed(q,key={_,b->b.relativeDir}){i,b->ListItem(headlineContent={Text(b.title,maxLines=2)},supportingContent={Text(b.seriesName?:"")},leadingContent={Text("${i+1}")},trailingContent={Row{IconButton({if(i>0)onMove(i,i-1)},enabled=i>0){Icon(Icons.Filled.KeyboardArrowUp,null)};IconButton({if(i<q.lastIndex)onMove(i,i+1)},enabled=i<q.lastIndex){Icon(Icons.Filled.KeyboardArrowDown,null)};IconButton({onRemove(b.relativeDir)}){Icon(Icons.Filled.Close,null)}}},modifier=Modifier.clickable{onPlay(b.relativeDir)});HorizontalDivider()}}}}

@Composable private fun HistoryScreen(modifier:Modifier,context:Context,books:List<AudioBookGroup>,onPlay:(AudioBookGroup)->Unit){val history=PlayerExtras.history(context);LazyColumn(modifier,contentPadding=PaddingValues(12.dp)){items(history,key={it.dir}){h->books.firstOrNull{it.relativeDir==h.dir}?.let{b->ListItem(headlineContent={Text(b.title)},supportingContent={Text("${b.seriesName?:"Без серії"} • ${relativeTime(h.at)}")},leadingContent={Cover(b,56.dp)},modifier=Modifier.clickable{onPlay(b)});HorizontalDivider()}}}}

@Composable private fun StatsScreen(modifier:Modifier,context:Context,books:List<AudioBookGroup>){val finished=books.count{bookProgress(context,it).finished};val started=books.count{bookProgress(context,it).started&&!bookProgress(context,it).finished};val listened=PlayerExtras.totalListenedMs(context);val daily=PlayerExtras.dailyListened(context);val fmt=remember{SimpleDateFormat("yyyy-MM-dd",Locale.US)};val last7=(0..6).map{days->fmt.format(Date(System.currentTimeMillis()-days*86_400_000L))}.reversed();Column(modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){Text("Прослухано загалом",style=MaterialTheme.typography.titleMedium);Text(formatMs(listened),style=MaterialTheme.typography.headlineMedium);ElevatedCard{Column(Modifier.padding(16.dp)){Text("Книг завершено: $finished");Text("Зараз слухаються: $started");Text("У бібліотеці: ${books.size}");Text("Серій: ${books.mapNotNull{it.seriesName}.distinct().size}")}};Text("Останні 7 днів",style=MaterialTheme.typography.titleMedium);last7.forEach{day->val ms=daily[day]?:0L;Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(day);Text(formatMs(ms))};LinearProgressIndicator({(ms.toFloat()/(daily.values.maxOrNull()?.coerceAtLeast(1L)?:1L)).coerceIn(0f,1f)},Modifier.fillMaxWidth())}}}

@Composable private fun SeriesScreen(modifier:Modifier,context:Context,books:List<AudioBookGroup>,onPlay:(AudioBookGroup)->Unit,onQueueAll:(List<AudioBookGroup>)->Unit){val sorted=books.sortedBy{it.seriesIndex};Column(modifier){Row(Modifier.padding(12.dp).fillMaxWidth(),horizontalArrangement=Arrangement.End){Button(onClick={onQueueAll(sorted)}){Icon(Icons.Filled.QueueMusic,null);Spacer(Modifier.width(4.dp));Text("У чергу всю серію")}};LazyColumn(contentPadding=PaddingValues(8.dp)){items(sorted,key={it.relativeDir}){b->LibraryRow(context,b,false,onPlay,{}, {}, {}, {})}}}}

@Composable private fun PlayerPane(modifier:Modifier,context:Context,tracks:List<AudioTrack>,index:Int,position:Long,duration:Long,playing:Boolean,speed:Float,sleepUntil:Long,sleepTrack:Boolean,cover:Bitmap?,siteCover:String?,showList:Boolean,bookmarksOpen:Boolean,lastError:String?,controller:MediaController?,brokenUris:Set<String>,onSpeed:(Float)->Unit,onSleep:(Int)->Unit,onSleepTrack:()->Unit,onToggleList:()->Unit,onToggleBookmarks:()->Unit,onBookmark:()->Unit,onTrack:(Int)->Unit,onPrevious:()->Unit,onNext:()->Unit){val current=tracks.getOrNull(index);val brokenIndices=PlayerLogic.brokenIndices(tracks.map{it.uri.toString()},brokenUris);val previousIndex=PlayerLogic.previousPlayableIndex(tracks.size,index,brokenIndices);val nextIndex=PlayerLogic.nextPlayableIndex(tracks.size,index,brokenIndices);Column(modifier.padding(horizontal=16.dp),horizontalAlignment=Alignment.CenterHorizontally){if(!lastError.isNullOrBlank())Text(lastError,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onClick={onSleep(if(sleepUntil>0)0 else 30)}){Icon(Icons.Filled.Bedtime,null);Text(if(sleepUntil>0)" Вимкнути" else " Сон 30 хв")};TextButton(onClick={onSleepTrack}){Text(if(sleepTrack)"Кінець файлу ✓" else "До кінця файлу")};TextButton(onClick={onSpeed(if(speed>=2f)1f else speed+0.25f)}){Text(String.format(Locale.US,"%.2fx",speed))};IconButton(onClick=onBookmark){Icon(Icons.Filled.BookmarkAdd,null)};IconButton(onClick=onToggleBookmarks){Icon(Icons.Filled.Bookmarks,null)};IconButton(onClick=onToggleList){Icon(Icons.Filled.PlaylistPlay,null)}}
    val bookFraction=PlayerLogic.currentBookProgress(tracks.size,index,position,duration,brokenIndices);val playableCount=PlayerLogic.playableCount(tracks.size,brokenIndices);LinearProgressIndicator({bookFraction},Modifier.fillMaxWidth());Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Книга ${(bookFraction*100).toInt()}%");Text("Файл ${index+1}/${tracks.size} • доступно $playableCount")};Spacer(Modifier.height(8.dp))
    when{bookmarksOpen->LazyColumn(Modifier.weight(1f).fillMaxWidth()){items(PlayerExtras.bookmarks(context).filter{b->tracks.any{it.uri.toString()==b.uri}},key={it.createdAt}){b->ListItem(headlineContent={Text(if(b.note.isBlank())"Закладка" else b.note)},supportingContent={Text(formatMs(b.position))},trailingContent={IconButton({PlayerExtras.deleteBookmark(context,b.createdAt)}){Icon(Icons.Filled.Delete,null)}},modifier=Modifier.clickable{tracks.indexOfFirst{it.uri.toString()==b.uri}.takeIf{it>=0&&it !in brokenIndices}?.let{i->onTrack(i);controller?.seekTo(b.position)}});HorizontalDivider()}}
        showList->LazyColumn(Modifier.weight(1f).fillMaxWidth()){itemsIndexed(tracks){i,t->val isBroken=i in brokenIndices;ListItem(headlineContent={Text(t.name,color=if(isBroken)MaterialTheme.colorScheme.error else LocalContentColor.current)},supportingContent={if(isBroken)Text("Файл недоступний — буде пропущено",color=MaterialTheme.colorScheme.error)},leadingContent={Icon(if(isBroken)Icons.Filled.BrokenImage else if(i==index)Icons.Filled.VolumeUp else Icons.Filled.AudioFile,null)},modifier=Modifier.clickable(enabled=!isBroken){onTrack(i)});HorizontalDivider()}}
        cover!=null->Image(cover.asImageBitmap(),current?.name,Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Fit)
        !siteCover.isNullOrBlank()->AsyncImage(siteCover,current?.name,Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)),contentScale=ContentScale.Fit)
        else->Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){Icon(Icons.Filled.MenuBook,null,Modifier.size(96.dp))}}
    Text(current?.name.orEmpty(),style=MaterialTheme.typography.titleMedium);Slider(if(duration>0)position.toFloat()/duration else 0f,{if(duration>0)controller?.seekTo((duration*it).toLong())});Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(formatMs(position));Text(formatMs(duration))};val seek=PlayerPrefs.seekSeconds(context)*1000L;Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically){IconButton(onPrevious,enabled=previousIndex!=null){Icon(Icons.Filled.SkipPrevious,null,Modifier.size(36.dp))};TextButton({controller?.seekTo((controller.currentPosition-seek).coerceAtLeast(0))}){Text("-${seek/1000}с")};FilledIconButton({controller?.let{if(it.isPlaying)it.pause()else it.play()}},Modifier.size(64.dp)){Icon(if(playing)Icons.Filled.Pause else Icons.Filled.PlayArrow,null,Modifier.size(36.dp))};TextButton({controller?.seekTo((controller.currentPosition+seek).coerceAtMost(controller.duration.coerceAtLeast(0)))}){Text("+${seek/1000}с")};IconButton(onNext,enabled=nextIndex!=null){Icon(Icons.Filled.SkipNext,null,Modifier.size(36.dp))}};Spacer(Modifier.height(12.dp))}}

private fun groupTracksIntoBooks(context:Context,tracks:List<AudioTrack>):List<AudioBookGroup>{val cat=trackerCatalog(context);return tracks.groupBy{normalizeBookDir(it.relativePath)}.map{(dir,items)->val title=dir.substringAfterLast('/').ifBlank{"Книга"};val m=matchTrackerBook(cat,title);AudioBookGroup(title,dir,items.sortedBy{naturalKey(it.name)},m?.series,m?.index?:Int.MAX_VALUE,m?.coverUrl,m?.status)}.sortedWith(compareBy<AudioBookGroup>{it.seriesName?:"~"}.thenBy{it.seriesIndex}.thenBy{it.title.lowercase()})}
private fun trackerCatalog(context:Context):List<TrackerBookMeta> = RoomTrackerCatalog.snapshot().map { TrackerBookMeta(it.title,it.series,it.index,it.coverUrl,it.status) }
private fun matchTrackerBook(c:List<TrackerBookMeta>,title:String):TrackerBookMeta?{val w=normalizeTitle(title);val n=bookNumber(title);return c.firstOrNull{normalizeTitle(it.title)==w}?:c.filter{n!=null&&bookNumber(it.title)==n}.maxByOrNull{commonTitleScore(w,normalizeTitle(it.title))}?:c.maxByOrNull{commonTitleScore(w,normalizeTitle(it.title))}?.takeIf{commonTitleScore(w,normalizeTitle(it.title))>=2}}
private fun commonTitleScore(a:String,b:String)=a.split(' ').filter{it.length>2}.toSet().intersect(b.split(' ').filter{it.length>2}.toSet()).size
internal fun bookNumber(v:String):Int?{val patterns=listOf(Regex("(?i)(?:книга|частина|часть|том|book)\\s*[№#:-]?\\s*(\\d{1,3})"),Regex("^\\s*(\\d{1,3})\\s*[-.:]"),Regex("(?:^|\\s)(\\d{1,3})(?:\\s*\\(\\d+\\))?(?:\\.|\\s|$)"));return patterns.firstNotNullOfOrNull{it.find(v)?.groupValues?.getOrNull(1)?.toIntOrNull()}}
private fun bookProgress(context:Context,b:AudioBookGroup):BookProgress{val positions=b.tracks.map{PlayerPrefs.position(context,it.uri)};val broken=PlayerLogic.brokenIndices(b.tracks.map{it.uri.toString()},PlayerExtras.brokenUris(context));val durations=b.tracks.mapIndexed{i,t->if(i in broken||positions.getOrElse(i){0L}<=0L)0L else mediaDuration(context,t.uri)};val p=PlayerLogic.bookProgress(positions,durations,broken,b.trackerStatus.equals("READ",true));return BookProgress(p.fraction,p.started,p.finished,p.currentTrack)}
private fun syncTrackerStatuses(context:Context,books:List<AudioBookGroup>){val updates=books.map{b->val pr=bookProgress(context,b);b.title to if(pr.finished)"READ" else if(pr.started)"READING" else (b.trackerStatus?:"NEW")};RoomTrackerCatalog.syncStatuses(context,updates)}
private fun mediaDuration(context:Context,uri:Uri):Long=runCatching{val m=MediaMetadataRetriever();try{m.setDataSource(context,uri);m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0}finally{m.release()}}.getOrDefault(0)
private fun normalizeBookDir(p:String)=p.replace('\\','/').trimEnd('/')
private fun logicalTrackKey(t:AudioTrack)="${normalizeBookDir(t.relativePath).lowercase()}/${t.name.lowercase()}"
private fun loadPlayerTracks(context:Context,dir:String?):List<AudioTrack>{val indexed=PlayerLibrary.forPath(context,dir).map{AudioTrack(Uri.parse(it.uri),it.name,it.relativePath)};val scanned=scanAudioTracks(context,dir);val m=linkedMapOf<String,AudioTrack>();(scanned+indexed).forEach{m[logicalTrackKey(it)]=it};return m.values.sortedWith(compareBy<AudioTrack>{normalizeBookDir(it.relativePath).lowercase()}.thenBy{naturalKey(it.name)})}
private fun scanAudioTracks(context:Context,dir:String?):List<AudioTrack>{val result=linkedMapOf<String,AudioTrack>();fun q(col:Uri,id:String,name:String,path:String){val sel=if(dir.isNullOrBlank())null else "$path LIKE ?";val args=if(dir.isNullOrBlank())null else arrayOf("%$dir%");runCatching{context.contentResolver.query(col,arrayOf(id,name,path),sel,args,"$name ASC")?.use{c->val ii=c.getColumnIndexOrThrow(id);val ni=c.getColumnIndexOrThrow(name);val pi=c.getColumnIndexOrThrow(path);while(c.moveToNext()){val n=c.getString(ni)?:continue;if(n.substringAfterLast('.',"").lowercase() !in setOf("mp3","m4a","m4b","ogg","opus","wav","aac","flac"))continue;val p=c.getString(pi).orEmpty();if(dir.isNullOrBlank()&&!p.contains("Audoiboo",true))continue;val t=AudioTrack(Uri.withAppendedPath(col,c.getLong(ii).toString()),n,p);result[logicalTrackKey(t)]=t}}}};if(Build.VERSION.SDK_INT>=29)q(MediaStore.Downloads.EXTERNAL_CONTENT_URI,MediaStore.Downloads._ID,MediaStore.Downloads.DISPLAY_NAME,MediaStore.Downloads.RELATIVE_PATH);q(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,MediaStore.Audio.Media._ID,MediaStore.Audio.Media.DISPLAY_NAME,MediaStore.Audio.Media.RELATIVE_PATH);return result.values.toList()}
private fun resolveBookCover(context:Context,tracks:List<AudioTrack>,preferred:Uri?=null):Bitmap?{val ordered=buildList{preferred?.let{p->tracks.firstOrNull{it.uri==p}?.let(::add)};tracks.forEach{if(none{x->x.uri==it.uri})add(it)}};for(t in ordered)embeddedCover(context,t.uri)?.let{return it};return null}
private fun embeddedCover(context:Context,uri:Uri):Bitmap?=runCatching{val m=MediaMetadataRetriever();try{m.setDataSource(context,uri);m.embeddedPicture?.let{BitmapFactory.decodeByteArray(it,0,it.size)}}finally{m.release()}}.getOrNull()
private fun siteCoverUrl(context:Context,title:String)=matchTrackerBook(trackerCatalog(context),title)?.coverUrl
private fun normalizeTitle(v:String)=v.lowercase().replace('ё','е').replace(Regex("[^a-zа-яіїєґ0-9]+")," ").trim()
private fun naturalKey(n:String)=n.lowercase().replace(Regex("(\\d+)")){it.value.padStart(12,'0')}
private fun formatMs(ms:Long):String{val t=ms/1000;val h=t/3600;val m=(t%3600)/60;val s=t%60;return if(h>0)"%d:%02d:%02d".format(h,m,s)else"%d:%02d".format(m,s)}
private fun relativeTime(at:Long):String{val d=(System.currentTimeMillis()-at).coerceAtLeast(0);return when{d<60_000->"щойно";d<3_600_000->"${d/60_000} хв тому";d<86_400_000->"${d/3_600_000} год тому";else->"${d/86_400_000} д тому"}}
