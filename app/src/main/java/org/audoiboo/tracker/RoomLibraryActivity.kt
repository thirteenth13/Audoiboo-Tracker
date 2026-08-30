package org.audoiboo.tracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.audoiboo.tracker.plugin.SeriesMatchDecisionEntity
import org.audoiboo.tracker.plugin.SourceMetadataRepository

class RoomLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { RoomLibraryScreen(this) } }
    }
}

private enum class RoomLibraryTab { SERIES, BOOKS, DOWNLOADS }

private data class PendingSeriesReview(
    val url: String,
    val fallbackToBrowser: Boolean,
    val review: RoomSeriesMatchReview
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomLibraryScreen(activity: ComponentActivity) {
    var tab by remember { mutableStateOf(RoomLibraryTab.SERIES) }
    var query by remember { mutableStateOf("") }
    var bookFilter by remember { mutableStateOf(RoomBookFilter.ALL) }
    var selectedSeries by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingReview by remember { mutableStateOf<PendingSeriesReview?>(null) }
    var discoveryReviews by remember { mutableStateOf<List<SeriesMatchDecisionEntity>>(emptyList()) }
    var reviewRefreshKey by remember { mutableIntStateOf(0) }
    var resolvingDiscoveryReview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val library by LibraryRepository.observe(activity).collectAsState(initial = emptyList())
    val pagingFlow = remember(query, bookFilter) { LibraryRepository.pagedBooks(activity, query, bookFilter) }
    val paged = pagingFlow.collectAsLazyPagingItems()
    val series = library.firstOrNull { it.series.id == selectedSeries }

    LaunchedEffect(selectedSeries, reviewRefreshKey) {
        discoveryReviews = selectedSeries
            ?.let { SourceMetadataRepository.pendingSeriesReviews(activity, it) }
            .orEmpty()
    }

    fun syncUrl(url: String, fallbackToBrowser: Boolean, resolution: RoomSeriesReviewResolution? = null) {
        if (url.isBlank() || syncing) return
        syncing = true
        scope.launch {
            val result = runCatching { RoomSeriesSync.sync(activity, url, resolution) }.getOrNull()
            syncing = false
            when {
                result?.review != null -> {
                    pendingReview = PendingSeriesReview(url, fallbackToBrowser, result.review)
                }
                result?.seriesId != null -> {
                    selectedSeries = result.seriesId
                    tab = RoomLibraryTab.SERIES
                    reviewRefreshKey++
                    Toast.makeText(activity, "${result.name}: ${result.books} книг", Toast.LENGTH_SHORT).show()
                }
                fallbackToBrowser -> {
                    Toast.makeText(activity, "HTTP parser не пройшов — відкриваю WebView fallback", Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(activity, MainActivity::class.java))
                }
                else -> Toast.makeText(activity, "Не вдалося оновити серію", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun resolveDiscoveryReview(review: SeriesMatchDecisionEntity, accept: Boolean) {
        val currentSeries = series ?: return
        if (resolvingDiscoveryReview) return
        resolvingDiscoveryReview = true
        scope.launch {
            val resolved = runCatching {
                SourceMetadataRepository.resolvePendingSeriesReview(
                    context = activity,
                    canonicalSeriesId = currentSeries.series.id,
                    sourceId = review.sourceId,
                    remoteKey = review.remoteKey,
                    accept = accept,
                    confidence = review.confidence
                )
            }.isSuccess
            resolvingDiscoveryReview = false
            reviewRefreshKey++
            if (!resolved) {
                Toast.makeText(activity, "Не вдалося зберегти рішення", Toast.LENGTH_LONG).show()
            } else if (accept) {
                Toast.makeText(activity, "Джерело підтверджено — оновлюю серію", Toast.LENGTH_SHORT).show()
                syncUrl(currentSeries.series.url, false)
            } else {
                Toast.makeText(activity, "Джерело відхилено", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (series != null) series.series.name else when (tab) { RoomLibraryTab.DOWNLOADS -> "Завантаження"; else -> "Audoiboo Tracker" }) },
                navigationIcon = { if (series != null) IconButton(onClick = { selectedSeries = null }) { Icon(Icons.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (series != null) {
                        IconButton(onClick = { syncUrl(series.series.url, false) }, enabled = !syncing) { Icon(Icons.Filled.Refresh, "Оновити") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Видалити серію") }
                    } else if (tab == RoomLibraryTab.SERIES) IconButton(onClick = { addUrl = ""; showAdd = true }) { Icon(Icons.Filled.Add, "Додати серію") }
                    IconButton(onClick = { activity.startActivity(Intent(activity, PlayerActivity::class.java)) }) { Icon(Icons.Filled.Headphones, "Плеєр") }
                    IconButton(onClick = { activity.startActivity(Intent(activity, MainActivity::class.java)) }) { Icon(Icons.Filled.Public, "Audioboo браузер") }
                    IconButton(onClick = { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }) { Icon(Icons.Filled.Settings, "Налаштування") }
                }
            )
        },
        bottomBar = {
            if (series == null) NavigationBar {
                NavigationBarItem(tab == RoomLibraryTab.SERIES, { tab = RoomLibraryTab.SERIES }, { Icon(Icons.Filled.MenuBook, null) }, label = { Text("Серії") })
                NavigationBarItem(tab == RoomLibraryTab.BOOKS, { tab = RoomLibraryTab.BOOKS }, { Icon(Icons.Filled.LibraryBooks, null) }, label = { Text("Книги") })
                NavigationBarItem(tab == RoomLibraryTab.DOWNLOADS, { tab = RoomLibraryTab.DOWNLOADS }, { Icon(Icons.Filled.Download, null) }, label = { Text("Завантаження") })
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (syncing || resolvingDiscoveryReview) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (series == null && tab != RoomLibraryTab.DOWNLOADS) {
                OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, label = { Text(if (tab == RoomLibraryTab.BOOKS) "Книга, автор або тег" else "Пошук серії") })
                if (tab == RoomLibraryTab.BOOKS) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RoomBookFilter.entries.forEach { filter -> FilterChip(selected = bookFilter == filter, onClick = { bookFilter = filter }, label = { Text(roomFilterLabel(filter)) }) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            when {
                series != null -> RoomSeriesDetail(
                    item = series,
                    pendingReviews = discoveryReviews,
                    reviewBusy = resolvingDiscoveryReview,
                    onResolveReview = ::resolveDiscoveryReview
                )
                tab == RoomLibraryTab.SERIES -> RoomSeriesList(library.filter { query.isBlank() || it.series.name.contains(query, true) }, onOpen = { selectedSeries = it })
                tab == RoomLibraryTab.DOWNLOADS -> ManagedDownloadsScreen(activity)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(paged.itemCount) { index -> paged[index]?.let { RoomBookCard(it, library.firstOrNull { s -> s.series.id == it.seriesId }?.series?.name) } }
                    if (paged.loadState.refresh is androidx.paging.LoadState.Loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    if (paged.loadState.append is androidx.paging.LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    val error = (paged.loadState.refresh as? androidx.paging.LoadState.Error)?.error ?: (paged.loadState.append as? androidx.paging.LoadState.Error)?.error
                    if (error != null) item { Text("Помилка Room/Paging: ${error.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }

    if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Додати серію") },
        text = { OutlinedTextField(addUrl, { addUrl = it }, label = { Text("URL серії або книги") }, modifier = Modifier.fillMaxWidth(), singleLine = true) },
        confirmButton = { TextButton(onClick = { val value = addUrl.trim(); showAdd = false; syncUrl(value, true) }, enabled = addUrl.startsWith("http")) { Text("Додати") } },
        dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Скасувати") } })

    pendingReview?.let { pending ->
        val percent = (pending.review.confidence * 100).toInt()
        AlertDialog(
            onDismissRequest = { pendingReview = null },
            title = { Text("Це та сама серія?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Нове джерело: ${pending.review.incomingName}")
                    Text("У бібліотеці: ${pending.review.candidateName}")
                    Text("Впевненість зіставлення: $percent%", style = MaterialTheme.typography.bodySmall)
                    if (pending.review.evidence.isNotEmpty()) {
                        Text("Ознаки: ${pending.review.evidence.joinToString(" • ")}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Підтвердження прив’яже нове джерело до існуючої серії. Відхилення збереже рішення і не пропонуватиме цей самий збіг повторно.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingReview = null
                    syncUrl(
                        pending.url,
                        pending.fallbackToBrowser,
                        RoomSeriesReviewResolution(pending.review.candidateSeriesId, accept = true, confidence = pending.review.confidence)
                    )
                }) { Text("Так, та сама") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingReview = null
                    syncUrl(
                        pending.url,
                        pending.fallbackToBrowser,
                        RoomSeriesReviewResolution(pending.review.candidateSeriesId, accept = false, confidence = pending.review.confidence)
                    )
                }) { Text("Ні, окрема") }
            }
        )
    }

    if (confirmDelete && series != null) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Видалити серію?") },
        text = { Text("${series.series.name}\n\nЗапис серії буде видалено з бібліотеки. Завантажені аудіофайли не видаляються.") },
        confirmButton = { TextButton(onClick = { val id = series.series.id; confirmDelete = false; scope.launch { LibraryRepository.deleteSeries(activity, id); selectedSeries = null } }) { Text("Видалити") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } })
}

@Composable
private fun RoomSeriesList(library: List<SeriesWithBooks>, onOpen: (String) -> Unit) {
    if (library.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ще немає доданих серій") }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(library, key = { it.series.id }) { item ->
            val firstCover = item.books.sortedBy { it.sortIndex }.firstOrNull()?.coverUrl
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(item.series.id) }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!firstCover.isNullOrBlank()) AsyncImage(firstCover, item.series.name, Modifier.size(58.dp)) else Icon(Icons.Filled.MenuBook, null, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) {
                        Text(item.series.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${item.books.size} книг • ${item.books.count { it.status != "READ" }} не прочитано • ${item.books.count { it.status == "NEW" }} нових", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomSeriesDetail(
    item: SeriesWithBooks,
    pendingReviews: List<SeriesMatchDecisionEntity>,
    reviewBusy: Boolean,
    onResolveReview: (SeriesMatchDecisionEntity, Boolean) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pendingReviews.isNotEmpty()) {
            item(key = "source-reviews") {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Rule, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Потрібна перевірка джерел", fontWeight = FontWeight.SemiBold)
                        }
                        pendingReviews.forEach { review ->
                            val percent = ((review.confidence ?: 0f) * 100).toInt()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(review.sourceId, fontWeight = FontWeight.Medium)
                                Text("Збіг із цією серією: $percent%", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onResolveReview(review, true) }, enabled = !reviewBusy) { Text("Підтвердити") }
                                    TextButton(onClick = { onResolveReview(review, false) }, enabled = !reviewBusy) { Text("Відхилити") }
                                }
                            }
                        }
                    }
                }
            }
        }
        items(item.books.sortedBy { it.sortIndex }, key = { it.id }) { book -> RoomBookCard(book, item.series.name) }
    }
}

@Composable
private fun RoomBookCard(book: BookEntity, seriesName: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tags by remember(book.id) { mutableStateOf<List<String>>(emptyList()) }
    var editTags by remember(book.id) { mutableStateOf(false) }
    var tagText by remember(book.id) { mutableStateOf("") }
    var resolvingArchive by remember(book.id) { mutableStateOf(false) }
    LaunchedEffect(book.id) { tags = LibraryRepository.bookWithTags(context, book.id)?.tags?.map { it.name }.orEmpty() }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!book.coverUrl.isNullOrBlank()) AsyncImage(book.coverUrl, book.title, Modifier.width(58.dp).height(82.dp)) else Icon(Icons.Filled.MenuBook, null, Modifier.size(48.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!seriesName.isNullOrBlank()) Text(seriesName, style = MaterialTheme.typography.bodySmall)
                if (!book.author.isNullOrBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { scope.launch { LibraryRepository.updateBookStatus(context, book.id, nextRoomStatus(book.status)) } }, label = { Text(roomStatusLabel(book.status)) })
                    AssistChip(onClick = { tagText = tags.joinToString(", "); editTags = true }, leadingIcon = { Icon(Icons.Filled.Label, null) }, label = { Text(if (tags.isEmpty()) "Теги" else tags.joinToString(" • "), maxLines = 1) })
                }
            }
            Column {
                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(book.url))) }) { Icon(Icons.Filled.OpenInBrowser, "Сторінка") }
                if (!book.archiveUrl.isNullOrBlank()) {
                    IconButton(onClick = { ManagedDownloads.enqueue(context, book.title, seriesName ?: "Без серії", book.author, book.url, book.archiveUrl) }) { Icon(Icons.Filled.CloudDownload, "Завантажити") }
                } else {
                    IconButton(onClick = {
                        if (!resolvingArchive) scope.launch {
                            resolvingArchive = true
                            val archive = runCatching { RoomArchiveResolver.resolve(context, book) }.getOrNull()
                            resolvingArchive = false
                            if (archive != null) {
                                ManagedDownloads.enqueue(context, book.title, seriesName ?: "Без серії", book.author, book.url, archive)
                                Toast.makeText(context, "Архів знайдено — додано в завантаження", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "HTTP parser не знайшов архів — відкриваю WebView fallback", Toast.LENGTH_LONG).show()
                                context.startActivity(Intent(context, MainActivity::class.java))
                            }
                        }
                    }, enabled = !resolvingArchive) { if (resolvingArchive) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Link, "Знайти архів") }
                }
            }
        }
    }

    if (editTags) AlertDialog(onDismissRequest = { editTags = false }, title = { Text("Теги: ${book.title}") },
        text = { OutlinedTextField(tagText, { tagText = it }, label = { Text("Через кому") }) },
        confirmButton = { TextButton(onClick = { val values = tagText.split(',').map { it.trim() }.filter { it.isNotBlank() }; scope.launch { LibraryRepository.setBookTags(context, book.id, values); tags = LibraryRepository.bookWithTags(context, book.id)?.tags?.map { it.name }.orEmpty(); editTags = false } }) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = { editTags = false }) { Text("Скасувати") } })
}

private fun roomFilterLabel(filter: RoomBookFilter): String = when (filter) { RoomBookFilter.ALL -> "Усі"; RoomBookFilter.NEW -> "Нові"; RoomBookFilter.READING -> "Читаю"; RoomBookFilter.READ -> "Прочитані"; RoomBookFilter.TAGGED -> "З тегами"; RoomBookFilter.UNTAGGED -> "Без тегів" }
private fun nextRoomStatus(status: String): String = when (status.uppercase()) { "NEW" -> "UNREAD"; "UNREAD" -> "READING"; "READING" -> "READ"; else -> "UNREAD" }
private fun roomStatusLabel(status: String): String = when (status.uppercase()) { "NEW" -> "Нова"; "UNREAD" -> "Не прочитано"; "READING" -> "Читаю"; "READ" -> "Прочитано"; else -> status }
