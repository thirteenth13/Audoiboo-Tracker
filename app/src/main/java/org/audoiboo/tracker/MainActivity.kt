package org.audoiboo.tracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

private enum class BookStatus { NEW, UNREAD, READING, READ }
private enum class MainTab { SERIES, DOWNLOADS }
private enum class SyncKind { SERIES, BOOK }

private data class Book(
    val title: String,
    val url: String,
    val author: String? = null,
    val coverUrl: String? = null,
    val status: BookStatus = BookStatus.NEW,
    val archiveUrl: String? = null
)

private data class Series(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val books: List<Book> = emptyList()
)

private data class SyncTask(
    val kind: SyncKind,
    val seriesId: String,
    val bookUrl: String? = null,
    val url: String
)

private data class ParsedSeriesPage(val books: List<Book>, val nextUrl: String?)

class MainActivity : ComponentActivity() {
    private var initialDark = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialDark = AppPrefs.darkTheme(this)
        setContent { AudoibooTheme(this) { TrackerScreen() } }
    }

    override fun onResume() {
        super.onResume()
        if (initialDark != AppPrefs.darkTheme(this)) recreate()
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
    var tab by remember { mutableStateOf(MainTab.SERIES) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val selectedSeries = library.firstOrNull { it.id == selectedSeriesId }
    fun commit(value: List<Series>) { library = value; saveLibrary(context, value) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Пошук") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Text(
                            when {
                                selectedSeries != null -> selectedSeries.name
                                tab == MainTab.DOWNLOADS -> "Завантаження"
                                else -> "Audoiboo Tracker"
                            }
                        )
                    }
                },
                navigationIcon = {
                    if (selectedSeries != null) {
                        IconButton(onClick = { selectedSeriesId = null; filter = null; query = "" }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                        }
                    }
                },
                actions = {
                    if (syncTask == null && tab == MainTab.SERIES) {
                        IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                            Icon(Icons.Filled.Search, contentDescription = "Пошук")
                        }
                    }
                    if (selectedSeries == null && tab == MainTab.SERIES) {
                        TextButton(onClick = { showAddDialog = true }) { Text("+ Серія") }
                    } else if (selectedSeries != null) {
                        IconButton(onClick = {
                            syncTask = SyncTask(SyncKind.SERIES, selectedSeries.id, url = selectedSeries.url)
                        }) { Icon(Icons.Filled.Refresh, contentDescription = "Оновити") }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.SERIES,
                    onClick = { tab = MainTab.SERIES },
                    icon = { Icon(Icons.Filled.MenuBook, contentDescription = null) },
                    label = { Text("Серії") }
                )
                NavigationBarItem(
                    selected = tab == MainTab.DOWNLOADS,
                    onClick = { tab = MainTab.DOWNLOADS; selectedSeriesId = null },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    label = { Text("Завантаження") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Налаштування") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                syncTask != null -> HiddenBrowserSync(
                    task = syncTask!!,
                    onSeriesParsed = { seriesId, parsedBooks ->
                        val current = library.firstOrNull { it.id == seriesId }
                        if (current != null) {
                            val old = current.books.associateBy { it.url }
                            val merged = parsedBooks.map { p ->
                                old[p.url]?.let { o ->
                                    p.copy(
                                        status = o.status,
                                        archiveUrl = o.archiveUrl,
                                        author = p.author ?: o.author,
                                        coverUrl = p.coverUrl ?: o.coverUrl
                                    )
                                } ?: p
                            }
                            commit(library.map { if (it.id == seriesId) it.copy(books = merged) else it })
                            Toast.makeText(context, "Знайдено книг: ${merged.size}", Toast.LENGTH_SHORT).show()
                        }
                        syncTask = null
                    },
                    onArchiveFound = { seriesId, bookUrl, archiveUrl ->
                        commit(library.map { s ->
                            if (s.id != seriesId) s else s.copy(books = s.books.map { b ->
                                if (b.url == bookUrl) b.copy(archiveUrl = archiveUrl) else b
                            })
                        })
                        Toast.makeText(context, "Архів знайдено", Toast.LENGTH_SHORT).show()
                        syncTask = null
                    },
                    onCancel = { syncTask = null }
                )

                tab == MainTab.DOWNLOADS -> ManagedDownloadsScreen(context)

                selectedSeries == null -> SeriesList(
                    library = library.filter { query.isBlank() || it.name.contains(query, true) },
                    onOpen = { selectedSeriesId = it },
                    onDelete = { id -> commit(library.filterNot { it.id == id }) }
                )

                else -> SeriesDetail(
                    series = selectedSeries,
                    filter = filter,
                    query = query,
                    onFilter = { filter = it },
                    onStatus = { book, status ->
                        commit(library.map { s ->
                            if (s.id != selectedSeries.id) s else s.copy(books = s.books.map { b ->
                                if (b.url == book.url) b.copy(status = status) else b
                            })
                        })
                    },
                    onOpenPage = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
                    onFindArchive = { book -> syncTask = SyncTask(SyncKind.BOOK, selectedSeries.id, book.url, book.url) },
                    onDownload = { book ->
                        book.archiveUrl?.let { archive ->
                            if (AppPrefs.wifiOnly(context) && !isOnWifi(context)) {
                                Toast.makeText(context, "Увімкнено завантаження лише по Wi‑Fi", Toast.LENGTH_LONG).show()
                            } else {
                                ManagedDownloads.enqueue(context, book.title, selectedSeries.name, book.author, book.url, archive)
                                Toast.makeText(context, "Додано до завантажень", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }

    if (showAddDialog) {
        AddSeriesDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                val series = Series(name = name.trim().ifBlank { suggestSeriesName(url) }, url = url.trim())
                commit(library + series)
                showAddDialog = false
                syncTask = SyncTask(SyncKind.SERIES, series.id, url = series.url)
            }
        )
    }
}

@Composable
private fun SeriesList(library: List<Series>, onOpen: (String) -> Unit, onDelete: (String) -> Unit) {
    if (library.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Додай першу серію кнопкою «+ Серія».")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(library, key = { it.id }) { series ->
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(series.id) },
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    val logo = series.books.firstOrNull()?.coverUrl
                    if (!logo.isNullOrBlank()) {
                        AsyncImage(
                            model = logo,
                            contentDescription = series.name,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.primary) }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(series.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${series.books.size} книг • ${series.books.count { it.status != BookStatus.READ }} не прочитано • ${series.books.count { it.status == BookStatus.NEW }} нових",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = { onDelete(series.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Видалити")
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesDetail(
    series: Series,
    filter: BookStatus?,
    query: String,
    onFilter: (BookStatus?) -> Unit,
    onStatus: (Book, BookStatus) -> Unit,
    onOpenPage: (String) -> Unit,
    onFindArchive: (Book) -> Unit,
    onDownload: (Book) -> Unit
) {
    val books = series.books.filter { book ->
        (filter == null || book.status == filter) &&
            (query.isBlank() || book.title.contains(query, true) || (book.author?.contains(query, true) == true))
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(filter == null, { onFilter(null) }, label = { Text("Всі") })
            FilterChip(filter == BookStatus.NEW, { onFilter(BookStatus.NEW) }, label = { Text("Нові") })
            FilterChip(filter == BookStatus.READING, { onFilter(BookStatus.READING) }, label = { Text("Читаю") })
            FilterChip(filter == BookStatus.READ, { onFilter(BookStatus.READ) }, label = { Text("Прочитані") })
        }

        if (series.books.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Оновлення серії…")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(books, key = { it.url }) { book ->
                    BookCard(book, onStatus, onOpenPage, onFindArchive, onDownload)
                }
            }
        }
    }
}

@Composable
private fun BookCard(
    book: Book,
    onStatus: (Book, BookStatus) -> Unit,
    onOpenPage: (String) -> Unit,
    onFindArchive: (Book) -> Unit,
    onDownload: (Book) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!book.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = book.coverUrl,
                    contentDescription = book.title,
                    modifier = Modifier.width(58.dp).height(82.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    Modifier.width(58.dp).height(82.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.MenuBook, null) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                book.author?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (book.status == BookStatus.NEW) SuggestionChip(onClick = {}, label = { Text("Нова") })
                    AssistChip(onClick = { onStatus(book, nextStatus(book.status)) }, label = { Text(statusLabel(book.status)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { onOpenPage(book.url) }) { Text("Сторінка") }
                    if (book.archiveUrl == null) TextButton(onClick = { onFindArchive(book) }) { Text("Знайти архів") }
                }
            }
            IconButton(onClick = { if (book.archiveUrl == null) onFindArchive(book) else onDownload(book) }) {
                Icon(
                    if (book.archiveUrl == null) Icons.Filled.Search else Icons.Filled.CloudDownload,
                    contentDescription = if (book.archiveUrl == null) "Знайти архів" else "Завантажити"
                )
            }
        }
    }
}

private fun statusLabel(status: BookStatus) = when (status) {
    BookStatus.NEW -> "Нова"
    BookStatus.UNREAD -> "Не прочитано"
    BookStatus.READING -> "Читаю"
    BookStatus.READ -> "Прочитано"
}

private fun nextStatus(status: BookStatus) = when (status) {
    BookStatus.NEW -> BookStatus.UNREAD
    BookStatus.UNREAD -> BookStatus.READING
    BookStatus.READING -> BookStatus.READ
    BookStatus.READ -> BookStatus.UNREAD
}

@Composable
private fun AddSeriesDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var lastSuggestion by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Додати серію") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { value ->
                        url = value
                        val suggestion = suggestSeriesName(value)
                        if (name.isBlank() || name == lastSuggestion) name = suggestion
                        lastSuggestion = suggestion
                    },
                    label = { Text("URL серії Audioboo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва серії") },
                    supportingText = { Text("Заповнюється автоматично з посилання") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name, url) }, enabled = url.startsWith("http") && name.isNotBlank()) { Text("Додати") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Скасувати") } }
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HiddenBrowserSync(
    task: SyncTask,
    onSeriesParsed: (String, List<Book>) -> Unit,
    onArchiveFound: (String, String, String) -> Unit,
    onCancel: () -> Unit
) {
    val collected = remember(task) { linkedMapOf<String, Book>() }
    val visited = remember(task) { mutableSetOf<String>() }
    var page by remember(task) { mutableIntStateOf(1) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(if (task.kind == SyncKind.SERIES) "Оновлення серії • сторінка $page" else "Пошук архіву")
            TextButton(onClick = onCancel) { Text("Скасувати") }
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
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
                                        if (next != null) {
                                            page += 1
                                            view.loadUrl(next)
                                        } else onSeriesParsed(task.seriesId, collected.values.toList())
                                    }
                                } else {
                                    view.evaluateJavascript(archiveParserJs) { raw ->
                                        val archive = decodeJsString(raw).takeIf { it.startsWith("http") }
                                        if (archive != null && task.bookUrl != null) onArchiveFound(task.seriesId, task.bookUrl, archive)
                                        else Toast.makeText(ctx, "Архів не знайдено", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }, 1000)
                        }
                    }
                    loadUrl(task.url)
                }
            },
            modifier = Modifier.size(1.dp).alpha(0f)
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
   if(!a)continue;
   const href=abs(a.getAttribute('href'));
   const title=norm(a.textContent);
   if(!href||!title)continue;
   let author=null;
   for(const li of card.querySelectorAll('li')){
     const text=norm(li.textContent);
     if(/^Автор:/i.test(text)){
       const aa=li.querySelector('a');
       author=norm(aa?aa.textContent:text.replace(/^Автор:\s*/i,''));
       break;
     }
   }
   const img=card.querySelector('img');
   const cover=img?abs(img.getAttribute('data-src')||img.getAttribute('src')):null;
   books.push({title:title,url:href,author:author,coverUrl:cover});
 }
 let current=1;
 const cm=location.pathname.match(/\/page\/(\d+)\/?$/i);
 if(cm)current=parseInt(cm[1],10);
 const base=location.pathname.replace(/\/page\/\d+\/?$/i,'/');
 const next=Array.from(document.querySelectorAll('a[href]'))
   .map(a=>abs(a.getAttribute('href'))).filter(Boolean)
   .map(h=>{try{const u=new URL(h);const m=u.pathname.match(/\/page\/(\d+)\/?$/i);if(!m)return null;const b=u.pathname.replace(/\/page\/\d+\/?$/i,'/');if(b!==base)return null;return{href:h,page:parseInt(m[1],10)}}catch(e){return null}})
   .filter(Boolean).filter(x=>x.page>current).sort((a,b)=>a.page-b.page)[0];
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
   if(href&&href.includes('/engine/go.php?url=')&&!text.includes('торрент')&&!href.startsWith('magnet:'))return href;
 }
 return '';
})();
""".trimIndent()

private fun decodeJsString(raw: String): String = try { JSONArray("[$raw]").getString(0) } catch (_: Exception) { raw.trim('"') }

private fun parseSeriesPage(raw: String): ParsedSeriesPage = try {
    val obj = JSONObject(decodeJsString(raw))
    val arr = obj.optJSONArray("books") ?: JSONArray()
    val books = (0 until arr.length()).mapNotNull { i ->
        val b = arr.optJSONObject(i) ?: return@mapNotNull null
        val title = b.optString("title").trim()
        val url = b.optString("url").trim()
        if (title.isBlank() || url.isBlank()) null else Book(
            title = title,
            url = url,
            author = b.optString("author").takeIf { it.isNotBlank() && it != "null" },
            coverUrl = b.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" }
        )
    }
    ParsedSeriesPage(books, obj.optString("nextUrl").takeIf { it.isNotBlank() && it != "null" })
} catch (_: Exception) { ParsedSeriesPage(emptyList(), null) }

private fun saveLibrary(context: Context, library: List<Series>) {
    val arr = JSONArray()
    library.forEach { series ->
        val obj = JSONObject().put("id", series.id).put("name", series.name).put("url", series.url)
        val books = JSONArray()
        series.books.forEach { book ->
            books.put(
                JSONObject()
                    .put("title", book.title)
                    .put("url", book.url)
                    .put("author", book.author)
                    .put("coverUrl", book.coverUrl)
                    .put("status", book.status.name)
                    .put("archiveUrl", book.archiveUrl)
            )
        }
        obj.put("books", books)
        arr.put(obj)
    }
    context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putString("library", arr.toString()).apply()
}

private fun loadLibrary(context: Context): List<Series> {
    val raw = context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", null) ?: return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            val ba = obj.optJSONArray("books") ?: JSONArray()
            val books = (0 until ba.length()).map { j ->
                val b = ba.getJSONObject(j)
                Book(
                    title = b.optString("title"),
                    url = b.optString("url"),
                    author = b.optString("author").takeIf { it.isNotBlank() && it != "null" },
                    coverUrl = b.optString("coverUrl").takeIf { it.isNotBlank() && it != "null" },
                    status = runCatching { BookStatus.valueOf(b.optString("status", "NEW")) }.getOrDefault(BookStatus.NEW),
                    archiveUrl = b.optString("archiveUrl").takeIf { it.isNotBlank() && it != "null" }
                )
            }
            Series(obj.optString("id", UUID.randomUUID().toString()), obj.optString("name"), obj.optString("url"), books)
        }
    } catch (_: Exception) { emptyList() }
}

private fun suggestSeriesName(url: String): String {
    if (url.isBlank()) return ""
    return runCatching {
        val path = Uri.parse(url.trim()).path.orEmpty().trim('/')
        val parts = path.split('/').filter { it.isNotBlank() }
        val raw = when {
            "cikl" in parts -> parts.getOrNull(parts.indexOf("cikl") + 1)
            else -> parts.lastOrNull()
        }.orEmpty()
        URLDecoder.decode(raw, "UTF-8").replace('+', ' ').trim()
    }.getOrDefault("")
}

private fun isOnWifi(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}
