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

private data class SyncTask(
    val kind: SyncKind,
    val seriesId: String,
    val bookUrl: String? = null,
    val url: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { TrackerScreen() } }
    }
}

@Composable
private fun TrackerScreen() {
    val context = LocalContext.current
    var library by remember { mutableStateOf(loadLibrary(context)) }
    var selectedSeriesId by remember { mutableStateOf<String?>(null) }
    var syncTask by remember { mutableStateOf<SyncTask?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<BookStatus?>(null) }

    val selectedSeries = library.firstOrNull { it.id == selectedSeriesId }

    fun commit(newLibrary: List<Series>) {
        library = newLibrary
        saveLibrary(context, newLibrary)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selectedSeries?.name ?: "Audoiboo Tracker") },
                navigationIcon = {
                    if (selectedSeries != null) {
                        TextButton(onClick = {
                            selectedSeriesId = null
                            filter = null
                        }) { Text("←") }
                    }
                },
                actions = {
                    if (selectedSeries == null) {
                        TextButton(onClick = { showAddDialog = true }) { Text("+ Серія") }
                    } else {
                        TextButton(onClick = {
                            syncTask = SyncTask(
                                kind = SyncKind.SERIES,
                                seriesId = selectedSeries.id,
                                url = selectedSeries.url
                            )
                        }) { Text("Оновити") }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                syncTask != null -> {
                    BrowserSync(
                        task = syncTask!!,
                        onSeriesParsed = { seriesId, parsedBooks ->
                            val currentSeries = library.firstOrNull { it.id == seriesId }
                            if (currentSeries != null) {
                                val oldByUrl = currentSeries.books.associateBy { it.url }
                                val merged = parsedBooks.map { parsed ->
                                    val old = oldByUrl[parsed.url]
                                    if (old != null) parsed.copy(status = old.status, archiveUrl = old.archiveUrl)
                                    else parsed
                                }
                                commit(library.map {
                                    if (it.id == seriesId) it.copy(books = merged) else it
                                })
                                Toast.makeText(context, "Знайдено книг: ${merged.size}", Toast.LENGTH_SHORT).show()
                            }
                            syncTask = null
                        },
                        onArchiveFound = { seriesId, bookUrl, archiveUrl ->
                            commit(library.map { series ->
                                if (series.id != seriesId) series else series.copy(
                                    books = series.books.map { book ->
                                        if (book.url == bookUrl) book.copy(archiveUrl = archiveUrl) else book
                                    }
                                )
                            })
                            Toast.makeText(context, "Посилання на архів збережено", Toast.LENGTH_SHORT).show()
                            syncTask = null
                        },
                        onCancel = { syncTask = null }
                    )
                }

                selectedSeries == null -> {
                    SeriesList(
                        library = library,
                        onOpen = { selectedSeriesId = it },
                        onDelete = { id ->
                            commit(library.filterNot { it.id == id })
                        }
                    )
                }

                else -> {
                    SeriesDetail(
                        series = selectedSeries,
                        filter = filter,
                        onFilter = { filter = it },
                        onStatus = { book, status ->
                            commit(library.map { series ->
                                if (series.id != selectedSeries.id) series else series.copy(
                                    books = series.books.map { current ->
                                        if (current.url == book.url) current.copy(status = status) else current
                                    }
                                )
                            })
                        },
                        onOpenPage = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        onFindArchive = { book ->
                            syncTask = SyncTask(
                                kind = SyncKind.BOOK,
                                seriesId = selectedSeries.id,
                                bookUrl = book.url,
                                url = book.url
                            )
                        },
                        onDownload = { book ->
                            book.archiveUrl?.let { downloadArchive(context, book, it) }
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddSeriesDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, url ->
                commit(library + Series(name = name.trim(), url = url.trim()))
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun SeriesList(
    library: List<Series>,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    if (library.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Додай першу серію кнопкою «+ Серія».")
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(library, key = { it.id }) { series ->
            val unread = series.books.count { it.status != BookStatus.READ }
            val newCount = series.books.count { it.status == BookStatus.NEW }
            ListItem(
                headlineContent = { Text(series.name) },
                supportingContent = {
                    Text("${series.books.size} книг • $unread не прочитано • $newCount нових")
                },
                trailingContent = {
                    TextButton(onClick = { onDelete(series.id) }) { Text("Видалити") }
                },
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
    val visibleBooks = if (filter == null) series.books else series.books.filter { it.status == filter }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = filter == null, onClick = { onFilter(null) }, label = { Text("Всі") })
            FilterChip(selected = filter == BookStatus.NEW, onClick = { onFilter(BookStatus.NEW) }, label = { Text("Нові") })
            FilterChip(selected = filter == BookStatus.READING, onClick = { onFilter(BookStatus.READING) }, label = { Text("Читаю") })
            FilterChip(selected = filter == BookStatus.READ, onClick = { onFilter(BookStatus.READ) }, label = { Text("Прочитані") })
        }

        if (series.books.isEmpty()) {
            Text(
                "Натисни «Оновити», щоб отримати список книг із Audioboo.",
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visibleBooks, key = { it.url }) { book ->
                    BookRow(
                        book = book,
                        onStatus = { onStatus(book, it) },
                        onOpenPage = { onOpenPage(book.url) },
                        onFindArchive = { onFindArchive(book) },
                        onDownload = { onDownload(book) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun BookRow(
    book: Book,
    onStatus: (BookStatus) -> Unit,
    onOpenPage: () -> Unit,
    onFindArchive: () -> Unit,
    onDownload: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(book.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(statusLabel(book.status), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onStatus(nextStatus(book.status)) }) {
                Text("Статус → ${statusLabel(nextStatus(book.status))}")
            }
            TextButton(onClick = onOpenPage) { Text("Сторінка") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (book.archiveUrl == null) {
                TextButton(onClick = onFindArchive) { Text("Знайти архів") }
            } else {
                TextButton(onClick = onDownload) { Text("Завантажити архів") }
                Text("Архів знайдено", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun statusLabel(status: BookStatus): String = when (status) {
    BookStatus.NEW -> "Нова"
    BookStatus.UNREAD -> "Не прочитано"
    BookStatus.READING -> "Читаю"
    BookStatus.READ -> "Прочитано"
}

private fun nextStatus(status: BookStatus): BookStatus = when (status) {
    BookStatus.NEW -> BookStatus.UNREAD
    BookStatus.UNREAD -> BookStatus.READING
    BookStatus.READING -> BookStatus.READ
    BookStatus.READ -> BookStatus.UNREAD
}

@Composable
private fun AddSeriesDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("Другая сторона") }
    var url by remember {
        mutableStateOf("https://audioboo.org/xfsearch/cikl/%D0%94%D1%80%D1%83%D0%B3%D0%B0%D1%8F%20%D1%81%D1%82%D0%BE%D1%80%D0%BE%D0%BD%D0%B0/")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Додати серію") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Назва") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL серії Audioboo") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && url.startsWith("http"),
                onClick = { onAdd(name, url) }
            ) { Text("Додати") }
        },
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
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(if (task.kind == SyncKind.SERIES) "Синхронізація серії…" else "Пошук архіву…")
            TextButton(onClick = onCancel) { Text("Закрити") }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "")
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    setDownloadListener { url, _, _, _, _ ->
                        if (task.kind == SyncKind.BOOK && task.bookUrl != null && url.isNotBlank()) {
                            onArchiveFound(task.seriesId, task.bookUrl, url)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.postDelayed({
                                if (task.kind == SyncKind.SERIES) {
                                    view.evaluateJavascript(seriesParserJs) { raw ->
                                        val books = parseSeriesResult(raw)
                                        if (books.isNotEmpty()) onSeriesParsed(task.seriesId, books)
                                    }
                                } else {
                                    view.evaluateJavascript(archiveParserJs) { raw ->
                                        val archive = decodeJsString(raw).takeIf { it.startsWith("http") }
                                        if (archive != null && task.bookUrl != null) {
                                            onArchiveFound(task.seriesId, task.bookUrl, archive)
                                        }
                                    }
                                }
                            }, 1200)
                        }
                    }
                    loadUrl(task.url)
                }
            }
        )
    }
}

private val seriesParserJs = """
(function() {
  const absolute = (u) => { try { return new URL(u, location.href).href; } catch(e) { return null; } };
  const seen = new Set();
  const result = [];
  const links = Array.from(document.querySelectorAll('a[href]'));
  for (const a of links) {
    const href = absolute(a.getAttribute('href'));
    if (!href || seen.has(href)) continue;
    const path = new URL(href).pathname;
    if (!/\/[^\/]+\/\d{4,}[^\/]*\.html$/i.test(path)) continue;
    const card = a.closest('article, .short, .shortstory, .story, .item, .news-item, li, div');
    const heading = card && card.querySelector('h1,h2,h3,h4,.title,.name,.book-name,.book_name');
    const title = ((heading && heading.textContent) || a.getAttribute('title') || a.textContent || '')
      .replace(/\s+/g, ' ').trim();
    if (!title || title.length < 3) continue;
    seen.add(href);
    result.push({ title: title, url: href });
  }
  return JSON.stringify(result);
})();
""".trimIndent()

private val archiveParserJs = """
(function() {
  const absolute = (u) => { try { return new URL(u, location.href).href; } catch(e) { return null; } };
  const els = Array.from(document.querySelectorAll('a[href], [data-href], [data-url], form[action]'));
  let fallback = null;
  for (const el of els) {
    const raw = el.getAttribute('href') || el.getAttribute('data-href') || el.getAttribute('data-url') || el.getAttribute('action');
    const href = raw ? absolute(raw) : null;
    if (!href) continue;
    const text = (el.textContent || el.getAttribute('title') || '').toLowerCase();
    if (/\.(zip|rar|7z)(\?|$)/i.test(href)) return href;
    if (!fallback && /(скачать|download|загрузить|архив)/i.test(text)) fallback = href;
  }
  return fallback || '';
})();
""".trimIndent()

private fun parseSeriesResult(raw: String): List<Book> = runCatching {
    val decoded = decodeJsString(raw)
    val arr = JSONArray(decoded)
    buildList {
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val title = obj.optString("title").trim()
            val url = obj.optString("url").trim()
            if (title.isNotBlank() && url.startsWith("http")) add(Book(title = title, url = url))
        }
    }
}.getOrDefault(emptyList())

private fun decodeJsString(raw: String): String {
    if (raw == "null" || raw.isBlank()) return ""
    return runCatching { JSONArray("[$raw]").getString(0) }
        .getOrElse { raw.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }
}

private fun loadLibrary(context: Context): List<Series> {
    val json = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
        .getString("library", null) ?: return emptyList()
    return runCatching {
        val root = JSONArray(json)
        buildList {
            for (i in 0 until root.length()) {
                val s = root.getJSONObject(i)
                val booksJson = s.optJSONArray("books") ?: JSONArray()
                val books = buildList {
                    for (j in 0 until booksJson.length()) {
                        val b = booksJson.getJSONObject(j)
                        add(
                            Book(
                                title = b.optString("title"),
                                url = b.optString("url"),
                                status = runCatching { BookStatus.valueOf(b.optString("status", "UNREAD")) }
                                    .getOrDefault(BookStatus.UNREAD),
                                archiveUrl = b.optString("archiveUrl").takeIf { it.isNotBlank() }
                            )
                        )
                    }
                }
                add(
                    Series(
                        id = s.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = s.optString("name", "Серія"),
                        url = s.optString("url"),
                        books = books
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

private fun saveLibrary(context: Context, library: List<Series>) {
    val root = JSONArray()
    library.forEach { series ->
        val books = JSONArray()
        series.books.forEach { book ->
            books.put(
                JSONObject()
                    .put("title", book.title)
                    .put("url", book.url)
                    .put("status", book.status.name)
                    .put("archiveUrl", book.archiveUrl ?: "")
            )
        }
        root.put(
            JSONObject()
                .put("id", series.id)
                .put("name", series.name)
                .put("url", series.url)
                .put("books", books)
        )
    }
    context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
        .edit().putString("library", root.toString()).apply()
}

private fun downloadArchive(context: Context, book: Book, url: String) {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(book.title)
        .setDescription("Audioboo archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            sanitizeFileName(book.title) + ".zip"
        )

    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
    request.addRequestHeader("Referer", book.url)

    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    manager.enqueue(request)
    Toast.makeText(context, "Завантаження додано", Toast.LENGTH_SHORT).show()
}

private fun sanitizeFileName(value: String): String =
    value.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "audioboo" }
