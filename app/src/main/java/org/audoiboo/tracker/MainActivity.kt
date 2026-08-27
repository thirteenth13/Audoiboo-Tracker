package org.audoiboo.tracker

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private enum class BookStatus { NEW, UNREAD, READING, READ }
private data class Book(val title: String, val url: String, val status: BookStatus = BookStatus.NEW, val archiveUrl: String? = null)
private data class Series(val id: String = UUID.randomUUID().toString(), val name: String, val url: String, val books: List<Book> = emptyList())
private enum class SyncKind { SERIES, BOOK }
private data class SyncTask(val kind: SyncKind, val seriesId: String, val bookUrl: String? = null, val url: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TrackerScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrackerScreen() {
    val context = LocalContext.current
    var library by remember { mutableStateOf(loadLibrary(context)) }
    var selectedSeriesId by remember { mutableStateOf<String?>(null) }
    var syncTask by remember { mutableStateOf<SyncTask?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<BookStatus?>(null) }
    val selectedSeries = library.firstOrNull { it.id == selectedSeriesId }
    fun commit(newLibrary: List<Series>) { library = newLibrary; saveLibrary(context, newLibrary) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(selectedSeries?.name ?: "Audoiboo Tracker") }, navigationIcon = {
            if (selectedSeries != null) TextButton(onClick = { selectedSeriesId = null; filter = null }) { Text("←") }
        }, actions = {
            if (selectedSeries == null) TextButton(onClick = { showAddDialog = true }) { Text("+ Серія") }
            else TextButton(onClick = { syncTask = SyncTask(SyncKind.SERIES, selectedSeries.id, url = selectedSeries.url) }) { Text("Оновити") }
        })
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                syncTask != null -> BrowserSync(syncTask!!, { seriesId, parsedBooks ->
                    val current = library.firstOrNull { it.id == seriesId }
                    if (current != null) {
                        val old = current.books.associateBy { it.url }
                        val merged = parsedBooks.map { p -> old[p.url]?.let { p.copy(status = it.status, archiveUrl = it.archiveUrl) } ?: p }
                        commit(library.map { if (it.id == seriesId) it.copy(books = merged) else it })
                        Toast.makeText(context, "Знайдено книг: ${merged.size}", Toast.LENGTH_SHORT).show()
                    }
                    syncTask = null
                }, { seriesId, bookUrl, archiveUrl ->
                    commit(library.map { s -> if (s.id != seriesId) s else s.copy(books = s.books.map { b -> if (b.url == bookUrl) b.copy(archiveUrl = archiveUrl) else b }) })
                    Toast.makeText(context, "Посилання на архів збережено", Toast.LENGTH_SHORT).show(); syncTask = null
                }, { syncTask = null })
                selectedSeries == null -> SeriesList(library, { selectedSeriesId = it }, { id -> commit(library.filterNot { it.id == id }) })
                else -> SeriesDetail(selectedSeries, filter, { filter = it }, { book, status ->
                    commit(library.map { s -> if (s.id != selectedSeries.id) s else s.copy(books = s.books.map { b -> if (b.url == book.url) b.copy(status = status) else b }) })
                }, { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }, { book -> syncTask = SyncTask(SyncKind.BOOK, selectedSeries.id, book.url, book.url) }, { book -> book.archiveUrl?.let { downloadArchive(context, book, it) } })
            }
        }
    }
    if (showAddDialog) AddSeriesDialog({ showAddDialog = false }) { name, url -> commit(library + Series(name = name.trim(), url = url.trim())); showAddDialog = false }
}

@Composable private fun SeriesList(library: List<Series>, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    if (library.isEmpty()) { Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Додай першу серію кнопкою «+ Серія».") }; return }
    LazyColumn(Modifier.fillMaxSize()) { items(library, key = { it.id }) { s ->
        ListItem(headlineContent = { Text(s.name) }, supportingContent = { Text("${s.books.size} книг • ${s.books.count { it.status != BookStatus.READ }} не прочитано • ${s.books.count { it.status == BookStatus.NEW }} нових") }, trailingContent = { TextButton(onClick = { onDelete(s.id) }) { Text("Видалити") } }, modifier = Modifier.clickable { onOpen(s.id) }); HorizontalDivider()
    } }
}

@Composable private fun SeriesDetail(series: Series, filter: BookStatus?, onFilter: (BookStatus?) -> Unit, onStatus: (Book, BookStatus) -> Unit, onOpenPage: (String) -> Unit, onFindArchive: (Book) -> Unit, onDownload: (Book) -> Unit) {
    val books = if (filter == null) series.books else series.books.filter { it.status == filter }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filter == null, { onFilter(null) }, label = { Text("Всі") }); FilterChip(filter == BookStatus.NEW, { onFilter(BookStatus.NEW) }, label = { Text("Нові") }); FilterChip(filter == BookStatus.READING, { onFilter(BookStatus.READING) }, label = { Text("Читаю") }); FilterChip(filter == BookStatus.READ, { onFilter(BookStatus.READ) }, label = { Text("Прочитані") })
        }
        if (series.books.isEmpty()) Text("Натисни «Оновити», щоб отримати список книг із Audioboo.", Modifier.padding(16.dp))
        else LazyColumn(Modifier.fillMaxSize()) { items(books, key = { it.url }) { b -> BookRow(b, { onStatus(b, it) }, { onOpenPage(b.url) }, { onFindArchive(b) }, { onDownload(b) }); HorizontalDivider() } }
    }
}

@Composable private fun BookRow(book: Book, onStatus: (BookStatus) -> Unit, onOpenPage: () -> Unit, onFindArchive: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp)) { Text(book.title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(4.dp)); Text(statusLabel(book.status), style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { TextButton({ onStatus(nextStatus(book.status)) }) { Text("Статус → ${statusLabel(nextStatus(book.status))}") }; TextButton(onOpenPage) { Text("Сторінка") } }; if (book.archiveUrl == null) TextButton(onFindArchive) { Text("Знайти архів") } else Row { TextButton(onDownload) { Text("Завантажити архів") }; Text("Архів знайдено") } }
}
private fun statusLabel(s: BookStatus) = when(s){ BookStatus.NEW->"Нова"; BookStatus.UNREAD->"Не прочитано"; BookStatus.READING->"Читаю"; BookStatus.READ->"Прочитано" }
private fun nextStatus(s: BookStatus) = when(s){ BookStatus.NEW->BookStatus.UNREAD; BookStatus.UNREAD->BookStatus.READING; BookStatus.READING->BookStatus.READ; BookStatus.READ->BookStatus.UNREAD }

@Composable private fun AddSeriesDialog(onDismiss: () -> Unit, onAdd: (String,String) -> Unit) {
    var name by remember { mutableStateOf("Другая сторона") }; var url by remember { mutableStateOf("https://audioboo.org/xfsearch/cikl/%D0%94%D1%80%D1%83%D0%B3%D0%B0%D1%8F%20%D1%81%D1%82%D0%BE%D1%80%D0%BE%D0%BD%D0%B0/") }
    AlertDialog(onDismissRequest=onDismiss, title={Text("Додати серію")}, text={Column { OutlinedTextField(name,{name=it},label={Text("Назва")}); OutlinedTextField(url,{url=it},label={Text("URL серії Audioboo")}) }}, confirmButton={TextButton({onAdd(name,url)}, enabled=name.isNotBlank()&&url.startsWith("http")){Text("Додати")}}, dismissButton={TextButton(onDismiss){Text("Скасувати")}})
}

@SuppressLint("SetJavaScriptEnabled") @Composable private fun BrowserSync(task: SyncTask, onSeriesParsed:(String,List<Book>)->Unit, onArchiveFound:(String,String,String)->Unit, onCancel:()->Unit) {
    Column(Modifier.fillMaxSize()) { Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement=Arrangement.SpaceBetween){Text(if(task.kind==SyncKind.SERIES)"Синхронізація серії…" else "Пошук архіву…");TextButton(onCancel){Text("Закрити")}}
        AndroidView(Modifier.fillMaxSize(), factory={ context -> WebView(context).apply { settings.javaScriptEnabled=true; settings.domStorageEnabled=true; CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(this,true); setDownloadListener{url,_,_,_,_->if(task.kind==SyncKind.BOOK&&task.bookUrl!=null&&url.isNotBlank())onArchiveFound(task.seriesId,task.bookUrl,url)}; webViewClient=object:WebViewClient(){override fun onPageFinished(view:WebView,url:String){view.postDelayed({if(task.kind==SyncKind.SERIES)view.evaluateJavascript(seriesParserJs){r->parseSeriesResult(r).takeIf{it.isNotEmpty()}?.let{onSeriesParsed(task.seriesId,it)}} else view.evaluateJavascript(archiveParserJs){r->decodeJsString(r).takeIf{it.startsWith("http")}?.let{a->task.bookUrl?.let{onArchiveFound(task.seriesId,it,a)}}}},1200)}}; loadUrl(task.url) } })
    }
}

private val seriesParserJs="""(function(){const a=u=>{try{return new URL(u,location.href).href}catch(e){return null}},s=new Set(),r=[];for(const x of document.querySelectorAll('a[href]')){const h=a(x.getAttribute('href'));if(!h||s.has(h))continue;const p=new URL(h).pathname;if(!/\/[^\/]+\/\d{4,}[^\/]*\.html$/i.test(p))continue;const c=x.closest('article,.short,.shortstory,.story,.item,.news-item,li,div'),q=c&&c.querySelector('h1,h2,h3,h4,.title,.name,.book-name,.book_name'),t=((q&&q.textContent)||x.title||x.textContent||'').replace(/\s+/g,' ').trim();if(t.length<3)continue;s.add(h);r.push({title:t,url:h})}return JSON.stringify(r)})()"""
private val archiveParserJs="""(function(){const a=u=>{try{return new URL(u,location.href).href}catch(e){return null}};for(const x of document.querySelectorAll('a[href]')){const h=a(x.getAttribute('href')),t=(x.textContent||'').toLowerCase();if(h&&(/\.(zip|rar|7z)(\?|$)/i.test(h)||/скач|download|архив/.test(t)))return h}return ''})()"""
private fun decodeJsString(raw:String):String=try{JSONArray("[$raw]").getString(0)}catch(_:Exception){raw.trim('"')}
private fun parseSeriesResult(raw:String):List<Book>=try{val text=decodeJsString(raw);val a=JSONArray(text);(0 until a.length()).mapNotNull{i->val o=a.optJSONObject(i)?:return@mapNotNull null;val t=o.optString("title").trim();val u=o.optString("url").trim();if(t.isBlank()||u.isBlank())null else Book(t,u)}}catch(_:Exception){emptyList()}
private fun saveLibrary(context:Context,library:List<Series>){val a=JSONArray();library.forEach{s->val o=JSONObject().put("id",s.id).put("name",s.name).put("url",s.url);val b=JSONArray();s.books.forEach{x->b.put(JSONObject().put("title",x.title).put("url",x.url).put("status",x.status.name).put("archiveUrl",x.archiveUrl))};o.put("books",b);a.put(o)};context.getSharedPreferences("tracker",Context.MODE_PRIVATE).edit().putString("library",a.toString()).apply()}
private fun loadLibrary(context:Context):List<Series>{val raw=context.getSharedPreferences("tracker",Context.MODE_PRIVATE).getString("library",null)?:return emptyList();return try{val a=JSONArray(raw);(0 until a.length()).map{i->val o=a.getJSONObject(i);val ba=o.optJSONArray("books")?:JSONArray();val books=(0 until ba.length()).map{j->val b=ba.getJSONObject(j);Book(b.optString("title"),b.optString("url"),runCatching{BookStatus.valueOf(b.optString("status","NEW"))}.getOrDefault(BookStatus.NEW),b.optString("archiveUrl").takeIf{it.isNotBlank()&&it!="null"})};Series(o.optString("id",UUID.randomUUID().toString()),o.optString("name"),o.optString("url"),books)}}catch(_:Exception){emptyList()}}
private fun downloadArchive(context:Context,book:Book,url:String){val request=DownloadManager.Request(Uri.parse(url)).setTitle(book.title).setDescription("Audioboo Tracker").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,safeFileName(book.title)+archiveExtension(url));CookieManager.getInstance().getCookie(url)?.let{request.addRequestHeader("Cookie",it)};request.addRequestHeader("Referer",book.url);(context.getSystemService(Context.DOWNLOAD_SERVICE)as DownloadManager).enqueue(request);Toast.makeText(context,"Завантаження розпочато",Toast.LENGTH_SHORT).show()}
private fun safeFileName(v:String)=v.replace(Regex("[\\/:*?\"<>|]"),"_").take(100)
private fun archiveExtension(url:String)=Regex("\\.(zip|rar|7z)(?:\\?|$)",RegexOption.IGNORE_CASE).find(url)?.value?.substringBefore('?')?:".zip"
