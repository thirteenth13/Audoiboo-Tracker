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

private data class ParsedSeriesPage(
    val books: List<Book>,
    val nextUrl: String?
)

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
                syncTask != null -> BrowserSync(
                    task = syncTask!!,
                    onSeriesParsed = { seriesId, parsedBooks ->
                        val current = library.firstOrNull { it.id == seriesId }
                        if (current != null) {
                            val oldByUrl = current.books.associateBy { it.url }
                            val merged = parsedBooks.map { parsed ->
                                oldByUrl[parsed.url]?.let { old ->
                                    parsed.copy(status = old.status, archiveUrl = old.archiveUrl)
                                } ?: parsed
                            }
                            commit(library.map {
                                if (it.id == seriesId) it.copy(books = merged) else it
                            })
                            Toast.makeText(
                                context,
                                "Знайдено книг: ${merged.size}",
                                Toast.LENGTH_SHORT
                            ).show()
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
                        Toast.makeText(
                            context,
                            "Посилання на архів збережено",
                            Toast.LENGTH_SHORT
                        ).show()
                        syncTask = null
                    },
                    onCancel = { syncTask = null }
                )

                selectedSeries == null -> SeriesList(
                    library = library,
                    onOpen = { selectedSeriesId = it },
                    onDelete = { id -> commit(library.filterNot { it.id == id }) }
                )

                else -> SeriesDetail(
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
            ListItem(
                headlineContent = { Text(series.name) },
                supportingContent = {
                    Text(
                        "${series.books.size} книг • " +
                            "${series.books.count { it.status != BookStatus.READ }} не прочитано • " +
                            "${series.books.count { it.status == BookStatus.NEW }} нових"
                    )
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
    val books = if (filter == null) series.books else series.books.filter { it.status == filter }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(filter == null, { onFilter(null) }, label = { Text("Всі") })
            FilterChip(filter == BookStatus.NEW, { onFilter(BookStatus.NEW) }, label = { Text("Нові") })
            FilterChip(filter == BookStatus.READING, { onFilter(BookStatus.READING) }, label = { Text("Читаю") })
            FilterChip(filter == BookStatus.READ, { onFilter(BookStatus.READ) }, label = { Text("Прочитані") })
        }

        if (series.books.isEmpty()) {
            Text(
                "Натисни «Оновити», щоб отримати список книг із Audioboo.",
                Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(books, key = { it.url }) { book ->
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
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text(book.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(statusLabel(book.status), style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onStatus(nextStatus(book.status)) }) {
                Text("Статус → ${statusLabel(nextStatus(book.status))}")
            }
            TextButton(onClick = onOpenPage) { Text("Сторінка") }
        }

        if (book.archiveUrl == null) {
            TextButton(onClick = onFindArchive) { Text("Знайти архів") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
        mutableStateOf(
            "https://audioboo.org/xfsearch/cikl/%D0%94%D1%80%D1%83%D0%B3%D0%B0%D1%8F%20%D1%81%D1%82%D0%BE%D1%80%D0%BE%D0%BD%D0%B0/"
        )
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
                onClick = { onAdd(name, url) },
                enabled = name.isNotBlank() && url.startsWith("http")
            ) { Text("Додати") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Скасувати") }
        }
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
    val collectedBooks = remember(task) { linkedMapOf<String, Book>() }
    val visitedPages = remember(task) { mutableSetOf<String>() }
    var pageNumber by remember(task) { mutableIntStateOf(1) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                if (task.kind == SyncKind.SERIES) {
                    "Синхронізація серії… сторінка $pageNumber"
                } else {
                    "Пошук правильного архіву…"
                }
            )
            TextButton(onClick = onCancel) { Text("Закрити") }
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    setDownloadListener { url, _, _, _, _ ->
                        if (
                            task.kind == SyncKind.BOOK &&
                            task.bookUrl != null &&
                            url.isNotBlank() &&
                            !url.startsWith("magnet:", ignoreCase = true)
                        ) {
                            onArchiveFound(task.seriesId, task.bookUrl, url)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            view.postDelayed({
                                if (task.kind == SyncKind.SERIES) {
                                    if (!visitedPages.add(url)) return@postDelayed

                                    view.evaluateJavascript(seriesParserJs) { raw ->
                                        val page = parseSeriesPage(raw)
                                        page.books.forEach { book -> collectedBooks[book.url] = book }

                                        val next = page.nextUrl
                                            ?.takeIf { it.isNotBlank() && it !in visitedPages }

                                        if (next != null) {
                                            pageNumber += 1
                                            view.loadUrl(next)
                                        } else {
                                            onSeriesParsed(
                                                task.seriesId,
                                                collectedBooks.values.toList()
                                            )
                                        }
                                    }
                                } else {
                                    view.evaluateJavascript(archiveParserJs) { raw ->
                                        val archive = decodeJsString(raw)
                                            .takeIf {
                                                it.startsWith("http") &&
                                                    !it.contains("torrent", ignoreCase = true)
                                            }
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
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/*
 * Сторінка циклу Audioboo має картки книг із кнопкою «Подробнее».
 * Саме ця кнопка використовується як надійна ознака книги — це відсікає
 * дати, меню, коментарі, рекламні та інші .html-посилання.
 *
 * Також повертається наступна сторінка /page/N/ для автоматичного обходу
 * багатосторінкових циклів.
 */
private val seriesParserJs = """
(function() {
  const abs = (u) => {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  };
  const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
  const books = [];
  const seen = new Set();

  const detailLinks = Array.from(document.querySelectorAll('a[href]')).filter(a => {
    const text = norm(a.textContent).toLowerCase();
    return text === 'подробнее' || text === 'детальніше';
  });

  for (const details of detailLinks) {
    const href = abs(details.getAttribute('href'));
    if (!href || seen.has(href)) continue;

    const card = details.closest(
      'article, .shortstory, .short, .story, .item, .news-item, .book-item, .short-item, .card'
    ) || details.parentElement?.parentElement?.parentElement || details.parentElement;

    let title = '';
    if (card) {
      const heading = card.querySelector(
        'h1, h2, h3, h4, .title, .short-title, .book-title, .book_name, .name'
      );
      title = norm(heading && heading.textContent);

      if (!title) {
        const image = card.querySelector('img[alt]');
        title = norm(image && image.getAttribute('alt'));
      }

      if (!title) {
        const candidates = Array.from(card.querySelectorAll('a[href]'))
          .filter(a => abs(a.getAttribute('href')) === href)
          .map(a => norm(a.textContent))
          .filter(t => t && t.toLowerCase() !== 'подробнее');
        title = candidates.sort((a, b) => b.length - a.length)[0] || '';
      }
    }

    if (!title) {
      try {
        const slug = new URL(href).pathname.split('/').pop().replace(/\.html$/i, '');
        title = decodeURIComponent(slug).replace(/^\d+-/, '').replace(/-/g, ' ');
      } catch (e) {}
    }

    if (!title) continue;
    seen.add(href);
    books.push({ title: title, url: href });
  }

  let currentPage = 1;
  const currentMatch = location.pathname.match(/\/page\/(\d+)\/?$/i);
  if (currentMatch) currentPage = parseInt(currentMatch[1], 10);

  const pageCandidates = Array.from(document.querySelectorAll('a[href]'))
    .map(a => abs(a.getAttribute('href')))
    .filter(Boolean)
    .map(href => {
      try {
        const u = new URL(href);
        const m = u.pathname.match(/\/page\/(\d+)\/?$/i);
        return m ? { href: href, page: parseInt(m[1], 10) } : null;
      } catch (e) { return null; }
    })
    .filter(Boolean)
    .filter(x => x.page > currentPage)
    .sort((a, b) => a.page - b.page);

  const nextUrl = pageCandidates.length ? pageCandidates[0].href : null;
  return JSON.stringify({ books: books, nextUrl: nextUrl });
})();
""".trimIndent()

/*
 * На сторінці книги є торрент, magnet і звичайна кнопка
 * «Скачать Автор - Название ...». Нам потрібна саме остання.
 * Торрент/магнет-посилання відсікаються явно.
 */
private val archiveParserJs = """
(function() {
  const abs = (u) => {
    try { return new URL(u, location.href).href; } catch (e) { return null; }
  };
  const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
  const candidates = [];

  for (const a of document.querySelectorAll('a[href]')) {
    const raw = a.getAttribute('href') || '';
    const href = abs(raw);
    const text = norm(a.textContent);
    const lower = text.toLowerCase();

    if (!href) continue;
    if (raw.toLowerCase().startsWith('magnet:')) continue;
    if (href.toLowerCase().startsWith('magnet:')) continue;
    if (lower.includes('торрент')) continue;
    if (lower.includes('примагнит')) continue;
    if (href.toLowerCase().includes('torrent')) continue;

    let score = 0;
    if (/^скачать\s+.+/i.test(text)) score += 100;
    if (/^завантажити\s+.+/i.test(text)) score += 100;
    if (/\.(zip|rar|7z)(?:\?|$)/i.test(href)) score += 40;
    if (lower === 'скачать' || lower === 'завантажити') score += 20;

    if (score > 0) candidates.push({ href: href, score: score, text: text });
  }

  candidates.sort((a, b) => b.score - a.score || b.text.length - a.text.length);
  return candidates.length ? candidates[0].href : '';
})();
""".trimIndent()

private fun decodeJsString(raw: String): String = try {
    JSONArray("[$raw]").getString(0)
} catch (_: Exception) {
    raw.trim('"')
}

private fun parseSeriesPage(raw: String): ParsedSeriesPage = try {
    val text = decodeJsString(raw)
    val obj = JSONObject(text)
    val array = obj.optJSONArray("books") ?: JSONArray()

    val books = (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val title = item.optString("title").trim()
        val url = item.optString("url").trim()
        if (title.isBlank() || url.isBlank()) null else Book(title, url)
    }

    val nextUrl = obj.optString("nextUrl")
        .takeIf { it.isNotBlank() && it != "null" }

    ParsedSeriesPage(books = books, nextUrl = nextUrl)
} catch (_: Exception) {
    ParsedSeriesPage(emptyList(), null)
}

private fun saveLibrary(context: Context, library: List<Series>) {
    val array = JSONArray()
    library.forEach { series ->
        val obj = JSONObject()
            .put("id", series.id)
            .put("name", series.name)
            .put("url", series.url)
        val books = JSONArray()
        series.books.forEach { book ->
            books.put(
                JSONObject()
                    .put("title", book.title)
                    .put("url", book.url)
                    .put("status", book.status.name)
                    .put("archiveUrl", book.archiveUrl)
            )
        }
        obj.put("books", books)
        array.put(obj)
    }
    context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
        .edit()
        .putString("library", array.toString())
        .apply()
}

private fun loadLibrary(context: Context): List<Series> {
    val raw = context.getSharedPreferences("tracker", Context.MODE_PRIVATE)
        .getString("library", null) ?: return emptyList()

    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val booksArray = obj.optJSONArray("books") ?: JSONArray()
            val books = (0 until booksArray.length()).map { j ->
                val book = booksArray.getJSONObject(j)
                Book(
                    title = book.optString("title"),
                    url = book.optString("url"),
                    status = runCatching {
                        BookStatus.valueOf(book.optString("status", "NEW"))
                    }.getOrDefault(BookStatus.NEW),
                    archiveUrl = book.optString("archiveUrl")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            }
            Series(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name"),
                url = obj.optString("url"),
                books = books
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun downloadArchive(context: Context, book: Book, url: String) {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(book.title)
        .setDescription("Audioboo Tracker")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            safeFileName(book.title) + archiveExtension(url)
        )

    CookieManager.getInstance().getCookie(url)?.let {
        request.addRequestHeader("Cookie", it)
    }
    request.addRequestHeader("Referer", book.url)

    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
    Toast.makeText(context, "Завантаження розпочато", Toast.LENGTH_SHORT).show()
}

private fun safeFileName(value: String): String =
    value.replace(Regex("[\\/:*?\"<>|]"), "_").take(100)

private fun archiveExtension(url: String): String =
    Regex("\\.(zip|rar|7z)(?:\\?|$)", RegexOption.IGNORE_CASE)
        .find(url)
        ?.value
        ?.substringBefore('?')
        ?: ".zip"
