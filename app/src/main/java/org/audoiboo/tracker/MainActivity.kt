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

private data class Book(
    val title: String,
    val url: String,
    val author: String? = null,
    val status: BookStatus = BookStatus.NEW,
    val archiveUrl: String? = null
)

private data class Series(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val books: List<Book> = emptyList()
)

private enum class SyncKind { SERIES, BOOK }
private data class SyncTask(val kind: SyncKind, val seriesId: String, val bookUrl: String? = null, val url: String)
private data class ParsedSeriesPage(val books: List<Book>, val nextUrl: String?)

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
    fun commit(value: List<Series>) { library = value; saveLibrary(context, value) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(selectedSeries?.name ?: "Audoiboo Tracker dev") },
            navigationIcon = {
                if (selectedSeries != null) TextButton(onClick = { selectedSeriesId = null; filter = null }) { Text("←") }
            },
            actions = {
                if (selectedSeries == null) {
                    TextButton(onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) }) { Text("⚙") }
                    TextButton(onClick = { showAddDialog = true }) { Text("+ Серія") }
                } else {
                    TextButton(onClick = { syncTask = SyncTask(SyncKind.SERIES, selectedSeries.id, url = selectedSeries.url) }) { Text("Оновити") }
                }
            }
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                syncTask != null -> BrowserSync(
                    task = syncTask!!,
                    onSeriesParsed = { seriesId, parsedBooks ->
                        val current = library.firstOrNull { it.id == seriesId }
                        if (current != null) {
                            val old = current.books.associateBy { it.url }
                            val merged = parsedBooks.map { p ->
                                old[p.url]?.let { o -> p.copy(status = o.status, archiveUrl = o.archiveUrl, author = p.author ?: o.author) } ?: p
                            }
                            commit(library.map { if (it.id == seriesId) it.copy(books = merged) else it })
                            Toast.makeText(context, "Знайдено книг: ${merged.size}", Toast.LENGTH_SHORT).show()
                        }
                        syncTask = null
                    },
                    onArchiveFound = { seriesId, bookUrl, archiveUrl ->
                        commit(library.map { s -> if (s.id != seriesId) s else s.copy(books = s.books.map { b -> if (b.url == bookUrl) b.copy(archiveUrl = archiveUrl) else b }) })
                        Toast.makeText(context, "Архів знайдено", Toast.LENGTH_SHORT).show()
                        syncTask = null
                    },
                    onCancel = { syncTask = null }
                )

                selectedSeries == null -> SeriesList(library, { selectedSeriesId = it }, { id -> commit(library.filterNot { it.id == id }) })

                else -> SeriesDetail(
                    series = selectedSeries,
                    filter = filter,
                    onFilter = { filter = it },
                    onStatus = { book, status ->
                        commit(library.map { s -> if (s.id != selectedSeries.id) s else s.copy(books = s.books.map { b -> if (b.url == book.url) b.copy(status = status) else b }) })
                    },
                    onOpenPage = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                    onFindArchive = { book -> syncTask = SyncTask(SyncKind.BOOK, selectedSeries.id, book.url, book.url) },
                    onDownload = { book -> book.archiveUrl?.let { downloadArchive(context, selectedSeries, book, it) } }
                )
            }
        }
    }

    if (showAddDialog) AddSeriesDialog(
        onDismiss = { showAddDialog = false },
        onAdd = { name, url -> commit(library + Series(name = name.trim(), url = url.trim())); showAddDialog = false }
    )
}

@Composable
private fun SeriesList(library: List<Series>, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    if (library.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Додай першу серію кнопкою «+ Серія».") }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(library, key = { it.id }) { series ->
            ListItem(
                headlineContent = { Text(series.name) },
                supportingContent = { Text("${series.books.size} книг • ${series.books.count { it.status != BookStatus.READ }} не прочитано • ${series.books.count { it.status == BookStatus.NEW }} нових") },
                trailingContent = { TextButton(onClick = { onDelete(series.id) }) { Text("Видалити") } },
                modifier = Modifier.clickable { onOpen(series.id) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun SeriesDetail(
    series: Series,
    filter: BookStatus?,
    onFilter: (BookStatus?) -> Unit,
    onStatus: (Book, BookStatus) -> Unit,
    onOpenPage: (String) -> Unit,
    onFindArchive: (Book) -> Unit,
    onDownload: (Book) -> Unit
) {
    val books = if (filter == null) series.books else series.books.filter { it.status == filter }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filter == null, { onFilter(null) }, label = { Text("Всі") })
            FilterChip(filter == BookStatus.NEW, { onFilter(BookStatus.NEW) }, label = { Text("Нові") })
            FilterChip(filter == BookStatus.READING, { onFilter(BookStatus.READING) }, label = { Text("Читаю") })
            FilterChip(filter == BookStatus.READ, { onFilter(BookStatus.READ) }, label = { Text("Прочитані") })
        }
        if (series.books.isEmpty()) Text("Натисни «Оновити», щоб отримати список книг із Audioboo.", Modifier.padding(16.dp))
        else LazyColumn(Modifier.fillMaxSize()) {
            items(books, key = { it.url }) { book ->
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium)
                    book.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text(statusLabel(book.status), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { onStatus(book, nextStatus(book.status)) }) { Text("Статус → ${statusLabel(nextStatus(book.status))}") }
                        TextButton(onClick = { onOpenPage(book.url) }) { Text("Сторінка") }
                    }
                    if (book.archiveUrl == null) TextButton(onClick = { onFindArchive(book) }) { Text("Знайти архів") }
                    else TextButton(onClick = { onDownload(book) }) { Text("Завантажити архів") }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun statusLabel(status: BookStatus) = when(status) {
    BookStatus.NEW -> "Нова"; BookStatus.UNREAD -> "Не прочитано"; BookStatus.READING -> "Читаю"; BookStatus.READ -> "Прочитано"
}
private fun nextStatus(status: BookStatus) = when(status) {
    BookStatus.NEW -> BookStatus.UNREAD; BookStatus.UNREAD -> BookStatus.READING; BookStatus.READING -> BookStatus.READ; BookStatus.READ -> BookStatus.UNREAD
}

@Composable
private fun AddSeriesDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Додати серію") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Назва") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("URL серії Audioboo") }, modifier = Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = { onAdd(name, url) }, enabled = name.isNotBlank() && url.startsWith("http")) { Text("Додати") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserSync(
    task: SyncTask,
    onSeriesParsed: (String, List<Book>) -> Unit,
    onArchiveFound: (String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    val collected = remember(task) { linkedMapOf<String, Book>() }
    val visited = remember(task) { mutableSetOf<String>() }
    var page by remember(task) { mutableIntStateOf(1) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (task.kind == SyncKind.SERIES) "Синхронізація… сторінка $page" else "Пошук архіву…")
            TextButton(onClick = onCancel) { Text("Закрити") }
        }
        AndroidView(
            factory = { ctx -> WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.postDelayed({
                            if (task.kind == SyncKind.SERIES) {
                                if (!visited.add(url)) return@postDelayed
                                view.evaluateJavascript(seriesParserJs) { raw ->
                                    val result = parseSeriesPage(raw)
                                    result.books.forEach { collected[it.url] = it }
                                    val next = result.nextUrl?.takeIf { it !in visited }
                                    if (next != null) { page += 1; view.loadUrl(next) }
                                    else onSeriesParsed(task.seriesId, collected.values.toList())
                                }
                            } else {
                                view.evaluateJavascript(archiveParserJs) { raw ->
                                    val archive = decodeJsString(raw).takeIf { it.startsWith("http") }
                                    if (archive != null && task.bookUrl != null) onArchiveFound(task.seriesId, task.bookUrl, archive)
                                }
                            }
                        }, 1200)
                    }
                }
                loadUrl(task.url)
            } },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private val seriesParserJs = """
(function(){
 const abs=u=>{try{return new URL(u,location.href).href}catch(e){return null}};
 const norm=s=>(s||'').replace(/\s+/g,' ').trim();
 const root=document.querySelector('#dle-content')||document.body;
 const books=[];
 for(const card of root.querySelectorAll('article.card')){
   const a=card.querySelector('h2.card__title a[href]');
   if(!a) continue;
   const href=abs(a.getAttribute('href'));
   const title=norm(a.textContent);
   if(!href||!title) continue;
   let author=null;
   for(const li of card.querySelectorAll('li')){
     const text=norm(li.textContent);
     if(/^Автор:/i.test(text)){
       const aa=li.querySelector('a');
       author=norm(aa?aa.textContent:text.replace(/^Автор:\s*/i,''));
       break;
     }
   }
   books.push({title:title,url:href,author:author});
 }
 let current=1; const cm=location.pathname.match(/\/page\/(\d+)\/?$/i); if(cm) current=parseInt(cm[1],10);
 const base=location.pathname.replace(/\/page\/\d+\/?$/i,'/');
 const next=Array.from(document.querySelectorAll('a[href]')).map(a=>abs(a.getAttribute('href'))).filter(Boolean).map(h=>{try{const u=new URL(h);const m=u.pathname.match(/\/page\/(\d+)\/?$/i);if(!m)return null;const b=u.pathname.replace(/\/page\/\d+\/?$/i,'/');if(b!==base)return null;return {href:h,page:parseInt(m[1],10)}}catch(e){return null}}).filter(Boolean).filter(x=>x.page>current).sort((a,b)=>a.page-b.page)[0];
 return JSON.stringify({books:books,nextUrl:next?next.href:null});
})();
""".trimIndent()

private val archiveParserJs = """
(function(){
 const abs=u=>{try{return new URL(u,location.href).href}catch(e){return null}};
 const links=Array.from(document.querySelectorAll('.black_button_olako a[href*="/engine/go.php?url="], a[href*="/engine/go.php?url="]'));
 for(const a of links){
   const text=(a.textContent||'').replace(/\s+/g,' ').trim().toLowerCase();
   const href=abs(a.getAttribute('href'));
   if(href && href.includes('/engine/go.php?url=') && !text.includes('торрент') && !href.startsWith('magnet:')) return href;
 }
 return '';
})();
""".trimIndent()

private fun decodeJsString(raw: String): String = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { raw.trim('"') }
private fun parseSeriesPage(raw: String): ParsedSeriesPage = try {
    val obj = JSONObject(decodeJsString(raw)); val arr = obj.optJSONArray("books") ?: JSONArray()
    val books = (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val title = o.optString("title").trim(); val url = o.optString("url").trim()
        if (title.isBlank() || url.isBlank()) null else Book(title = title, url = url, author = o.optString("author").takeIf { it.isNotBlank() && it != "null" })
    }
    ParsedSeriesPage(books, obj.optString("nextUrl").takeIf { it.isNotBlank() && it != "null" })
} catch (_: Exception) { ParsedSeriesPage(emptyList(), null) }

private fun saveLibrary(context: Context, library: List<Series>) {
    val a = JSONArray(); library.forEach { s ->
        val o = JSONObject().put("id", s.id).put("name", s.name).put("url", s.url); val b = JSONArray()
        s.books.forEach { x -> b.put(JSONObject().put("title", x.title).put("url", x.url).put("author", x.author).put("status", x.status.name).put("archiveUrl", x.archiveUrl)) }
        o.put("books", b); a.put(o)
    }
    context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putString("library", a.toString()).apply()
}

private fun loadLibrary(context: Context): List<Series> {
    val raw = context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", null) ?: return emptyList()
    return try { val a = JSONArray(raw); (0 until a.length()).map { i ->
        val o = a.getJSONObject(i); val ba = o.optJSONArray("books") ?: JSONArray(); val books = (0 until ba.length()).map { j ->
            val b = ba.getJSONObject(j); Book(
                title = b.optString("title"), url = b.optString("url"), author = b.optString("author").takeIf { it.isNotBlank() && it != "null" },
                status = runCatching { BookStatus.valueOf(b.optString("status", "NEW")) }.getOrDefault(BookStatus.NEW),
                archiveUrl = b.optString("archiveUrl").takeIf { it.isNotBlank() && it != "null" }
            )
        }; Series(id = o.optString("id", UUID.randomUUID().toString()), name = o.optString("name"), url = o.optString("url"), books = books)
    } } catch (_: Exception) { emptyList() }
}

private fun downloadArchive(context: Context, series: Series, book: Book, url: String) {
    val base = safePathPart(AppPrefs.baseFolder(context))
    val seriesFolder = safePathPart(series.name)
    val author = book.author?.takeIf { it.isNotBlank() }?.let(::safePathPart)
    val parts = mutableListOf(base, seriesFolder)
    if (AppPrefs.useAuthorFolder(context) && author != null) parts += author
    val file = safeFileName(book.title) + archiveExtension(url)
    val relative = (parts + file).joinToString("/")

    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(book.title)
        .setDescription("Audoiboo Tracker dev")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, relative)
    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
    request.addRequestHeader("Referer", book.url)
    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    Toast.makeText(context, "Downloads/$relative", Toast.LENGTH_LONG).show()
}

private fun safePathPart(value: String) = value.replace(Regex("[\\/:*?\"<>|]"), "_").trim().ifBlank { "Unknown" }.take(80)
private fun safeFileName(value: String) = safePathPart(value).take(120)
private fun archiveExtension(url: String) = Regex("\\.(zip|rar|7z)(?:\\?|$)", RegexOption.IGNORE_CASE).find(url)?.value?.substringBefore('?') ?: ".zip"
