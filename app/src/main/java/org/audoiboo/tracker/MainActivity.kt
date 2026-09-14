package org.audoiboo.tracker

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.HostPluginHttpTransport
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.tts.EnqueuedFlibustaBookTts
import org.audoiboo.tracker.tts.FlibustaBookTtsFlow
import org.audoiboo.tracker.tts.TtsGenerationScheduler
import org.audoiboo.tracker.tts.TtsQuality
import org.audoiboo.tracker.tts.TtsSessionState
import org.audoiboo.tracker.tts.TtsSessionStore

private const val SOURCE_BROWSER_DEFAULT_HOME = "https://audioboo.org/"
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); PluginPackageRuntime.initialize(filesDir); setContent { AudoibooTheme(this) { SourceBrowserScreen(this, intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith("http") } ?: SOURCE_BROWSER_DEFAULT_HOME) } } }
    companion object { const val EXTRA_URL = "source_url" }
}

@SuppressLint("SetJavaScriptEnabled") @OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SourceBrowserScreen(activity: ComponentActivity, initialUrl: String) {
    val scope = rememberCoroutineScope(); var currentUrl by remember { mutableStateOf(initialUrl) }; var address by remember { mutableStateOf(initialUrl) }; var webView by remember { mutableStateOf<WebView?>(null) }
    var syncing by remember { mutableStateOf(false) }; var preparingTts by remember { mutableStateOf(false) }; var activeTts by remember { mutableStateOf<EnqueuedFlibustaBookTts?>(null) }; var ttsState by remember { mutableStateOf<TtsSessionState?>(null) }
    var ttsCompletedChunks by remember { mutableIntStateOf(0) }; var ttsError by remember { mutableStateOf<String?>(null) }; var ttsMonitorRevision by remember { mutableIntStateOf(0) }; var ttsQuality by remember { mutableStateOf(TtsQuality.FAST) }
    val ttsBusy = preparingTts || ttsState == TtsSessionState.QUEUED || ttsState == TtsSessionState.RUNNING

    LaunchedEffect(activeTts?.session?.sessionId, ttsMonitorRevision) {
        val job = activeTts ?: return@LaunchedEffect; val store = TtsSessionStore(File(activity.filesDir, "tts/sessions")); val wm = WorkManager.getInstance(activity.applicationContext)
        while (true) { val snapshot = withContext(Dispatchers.IO) { store.load(job.session.sessionId) }; if (snapshot != null) { ttsState=snapshot.state; ttsCompletedChunks=snapshot.nextGlobalChunkIndex.coerceAtMost(job.chunkCount); ttsError=snapshot.lastError }
            when (withContext(Dispatchers.IO) { wm.getWorkInfosForUniqueWork(TtsGenerationScheduler.workName(job.session.sessionId)).get().firstOrNull()?.state }) {
                WorkInfo.State.SUCCEEDED -> { ttsState=TtsSessionState.COMPLETED; ttsCompletedChunks=job.chunkCount; ttsError=null; Toast.makeText(activity,"Озвучення завершено: ${job.title}",Toast.LENGTH_LONG).show(); break }
                WorkInfo.State.FAILED -> { ttsState=TtsSessionState.FAILED; if(ttsError.isNullOrBlank()) ttsError="Фонове озвучення завершилося з помилкою"; break }
                WorkInfo.State.CANCELLED -> { ttsState=TtsSessionState.PAUSED; ttsError=null; break }; else -> Unit }; delay(1000) }
    }
    fun navigate(raw:String){ val v=raw.trim(); if(v.isBlank())return; val target=if(v.startsWith("http://")||v.startsWith("https://"))v else "https://$v"; address=target; webView?.loadUrl(target) }
    fun addCurrentPage(){ val url=currentUrl.trim(); if(url.isBlank()||syncing)return; webView?.settings?.userAgentString?.let(HostPluginHttpTransport::updateBrowserUserAgent); runCatching{CookieManager.getInstance().flush()}; syncing=true; scope.launch { val plugin=PluginPackageRuntime.registry.forUrl(url); val result=runCatching{RoomSeriesSync.sync(activity,url)}.getOrNull(); syncing=false; when { result?.seriesId!=null->Toast.makeText(activity,"${result.name}: ${result.books} книг додано",Toast.LENGTH_LONG).show(); result?.review!=null->Toast.makeText(activity,"${result.name}: серію знайдено (${result.books} книг), але збіг із бібліотекою потребує підтвердження",Toast.LENGTH_LONG).show(); plugin==null->Toast.makeText(activity,"Для цього сайту немає активного плагіна",Toast.LENGTH_LONG).show(); else->Toast.makeText(activity,"Плагін ${plugin.descriptor.name} не зміг визначити серію з цієї сторінки",Toast.LENGTH_LONG).show() } } }
    fun startFlibustaTts(){ val url=currentUrl.trim(); if(!isFlibustaBookUrl(url)||ttsBusy)return; preparingTts=true; ttsError=null; val selected=ttsQuality; scope.launch { val result=withContext(Dispatchers.IO){FlibustaBookTtsFlow.create(activity).enqueue(activity,url,File(activity.filesDir,"tts/output"),quality=selected)}; preparingTts=false; result.onSuccess{job->activeTts=job;ttsState=TtsSessionState.QUEUED;ttsCompletedChunks=job.session.nextGlobalChunkIndex;Toast.makeText(activity,"Озвучення поставлено в чергу • ${job.session.voice.displayName}",Toast.LENGTH_LONG).show()}.onFailure{e->ttsError=e.message?:"невідома помилка";Toast.makeText(activity,"Не вдалося запустити озвучення: $ttsError",Toast.LENGTH_LONG).show()} } }
    fun pauseTts(job:EnqueuedFlibustaBookTts)=TtsGenerationScheduler.pause(activity.applicationContext,job.session.sessionId)
    fun resumeTts(job:EnqueuedFlibustaBookTts){runCatching{TtsGenerationScheduler.resume(activity.applicationContext,job.session.sessionId,job.title)}.onSuccess{ttsState=TtsSessionState.QUEUED;ttsError=null;ttsMonitorRevision++}.onFailure{e->ttsState=TtsSessionState.FAILED;ttsError=e.message?:"Не вдалося продовжити озвучення"}}
    fun openGeneratedBook(job:EnqueuedFlibustaBookTts){activity.startActivity(Intent(activity,PlayerActivity::class.java).apply{putExtra("relativeDir",job.relativeDir);putExtra("title",job.title)})}
    BackHandler(enabled=webView?.canGoBack()==true){webView?.goBack()}
    Scaffold(topBar={TopAppBar(title={Text("Браузер джерел")},navigationIcon={IconButton(onClick={if(webView?.canGoBack()==true)webView?.goBack() else activity.finish()}){Icon(Icons.Filled.ArrowBack,"Назад")}},actions={if(isFlibustaBookUrl(currentUrl)){IconButton(onClick=::startFlibustaTts,enabled=!ttsBusy){Icon(Icons.Filled.VolumeUp,"Озвучити книгу локально")}};IconButton(onClick={webView?.loadUrl(SOURCE_BROWSER_DEFAULT_HOME)}){Icon(Icons.Filled.Home,"Головна")};IconButton(onClick={webView?.reload()}){Icon(Icons.Filled.Refresh,"Оновити")};IconButton(onClick=::addCurrentPage,enabled=!syncing){Icon(Icons.Filled.Add,"Додати поточну сторінку")}})}){padding->Column(Modifier.padding(padding).fillMaxSize()){
        OutlinedTextField(address,{address=it},Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=6.dp),singleLine=true,label={Text("URL будь-якого джерела")},trailingIcon={TextButton(onClick={navigate(address)}){Text("Відкрити")}})
        if(isFlibustaBookUrl(currentUrl)&&activeTts==null){ SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp)){ SegmentedButton(selected=ttsQuality==TtsQuality.FAST,onClick={ttsQuality=TtsQuality.FAST},shape=SegmentedButtonDefaults.itemShape(0,2),enabled=!ttsBusy){Text("Швидко")}; SegmentedButton(selected=ttsQuality==TtsQuality.HIGH_QUALITY,onClick={ttsQuality=TtsQuality.HIGH_QUALITY},shape=SegmentedButtonDefaults.itemShape(1,2),enabled=!ttsBusy){Text("Висока якість")}} }
        if(syncing||preparingTts)LinearProgressIndicator(Modifier.fillMaxWidth())
        activeTts?.let{job->ElevatedCard(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp)){Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Text(job.title,style=MaterialTheme.typography.titleSmall);Text("Режим: ${if(job.session.quality==TtsQuality.HIGH_QUALITY) "Висока якість" else "Швидко"}",style=MaterialTheme.typography.bodySmall);Text(when(ttsState){TtsSessionState.QUEUED->"Озвучення: у черзі";TtsSessionState.RUNNING->"Озвучення: виконується";TtsSessionState.PAUSED->"Озвучення: призупинено";TtsSessionState.COMPLETED->"Озвучення завершено";TtsSessionState.FAILED->"Озвучення завершилося з помилкою";null->"Озвучення: підготовка"});if(job.chunkCount>0&&ttsState!=TtsSessionState.COMPLETED){val c=ttsCompletedChunks.coerceIn(0,job.chunkCount);Text("Прогрес: $c/${job.chunkCount} фрагментів • ${c*100/job.chunkCount}%",style=MaterialTheme.typography.bodySmall);LinearProgressIndicator(Modifier.fillMaxWidth())};ttsError?.takeIf{ttsState==TtsSessionState.FAILED}?.let{Text(it,color=MaterialTheme.colorScheme.error)};when(ttsState){TtsSessionState.QUEUED,TtsSessionState.RUNNING->OutlinedButton({pauseTts(job)},Modifier.fillMaxWidth()){Text("Призупинити озвучення")};TtsSessionState.PAUSED->Button({resumeTts(job)},Modifier.fillMaxWidth()){Text("Продовжити озвучення")};TtsSessionState.FAILED->Button({resumeTts(job)},Modifier.fillMaxWidth()){Text("Повторити з останнього фрагмента")};TtsSessionState.COMPLETED->Button({openGeneratedBook(job)},Modifier.fillMaxWidth()){Text("Відкрити готову книгу в плеєрі")};null->Unit}}}}
        AndroidView(factory={context->WebView(context).apply{settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.mediaPlaybackRequiresUserGesture=false;HostPluginHttpTransport.updateBrowserUserAgent(settings.userAgentString);CookieManager.getInstance().setAcceptCookie(true);CookieManager.getInstance().setAcceptThirdPartyCookies(this,true);webViewClient=object:WebViewClient(){override fun shouldOverrideUrlLoading(view:WebView?,request:WebResourceRequest?)=false;override fun onPageFinished(view:WebView?,url:String?){view?.settings?.userAgentString?.let(HostPluginHttpTransport::updateBrowserUserAgent);runCatching{CookieManager.getInstance().flush()};url.orEmpty().takeIf{it.isNotBlank()}?.let{currentUrl=it;address=it}}};webView=this;loadUrl(initialUrl)}},modifier=Modifier.fillMaxSize()) }}
}
internal fun isFlibustaBookUrl(url:String):Boolean=runCatching{val uri=URI(url);val scheme=uri.scheme?.lowercase()?:return@runCatching false;if(scheme!="http"&&scheme!="https")return@runCatching false;val host=uri.host?.lowercase()?:return@runCatching false;(host=="flibusta.site"||host=="flibusta.one"||host=="flibusta.name")&&Regex("^/b/[^/]+/?$").matches(uri.path.orEmpty())}.getOrDefault(false)
