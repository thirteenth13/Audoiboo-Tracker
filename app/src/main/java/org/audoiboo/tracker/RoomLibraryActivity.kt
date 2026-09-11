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
import org.audoiboo.tracker.plugin.PendingBookReview
import org.audoiboo.tracker.plugin.SeriesMatchDecisionEntity
import org.audoiboo.tracker.plugin.SourceMetadataRepository

class RoomLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { RoomLibraryScreen(this) } }
    }
}

private enum class RoomLibraryTab { SERIES, BOOKS, DOWNLOADS }
private data class PendingSeriesReview(val url: String, val fallbackToBrowser: Boolean, val review: RoomSeriesMatchReview)

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
    var bookReviews by remember { mutableStateOf<List<PendingBookReview>>(emptyList()) }
    var reviewRefreshKey by remember { mutableIntStateOf(0) }
    var resolvingDiscoveryReview by remember { mutableStateOf(false) }
    var resolvingBookReview by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val library by LibraryRepository.observe(activity).collectAsState(initial = emptyList())
    val pagingFlow = remember(query, bookFilter) { LibraryRepository.pagedBooks(activity, query, bookFilter) }
    val paged = pagingFlow.collectAsLazyPagingItems()
    val series = library.firstOrNull { it.series.id == selectedSeries }

    LaunchedEffect(selectedSeries, reviewRefreshKey) {
        val id = selectedSeries
        discoveryReviews = id?.let { SourceMetadataRepository.pendingSeriesReviews(activity, it) }.orEmpty()
        bookReviews = id?.let { SourceMetadataRepository.pendingBookReviews(activity, it) }.orEmpty()
    }

    fun openSourceBrowser(url: String? = null) {
        activity.startActivity(Intent(activity, MainActivity::class.java).apply {
            url?.takeIf { it.startsWith("http") }?.let { putExtra(MainActivity.EXTRA_URL, it) }
        })
    }

    fun syncUrl(url: String, fallbackToBrowser: Boolean, resolution: RoomSeriesReviewResolution? = null) {
        if (url.isBlank() || syncing) return
        syncing = true
        scope.launch {
            val result = runCatching { RoomSeriesSync.sync(activity, url, resolution) }.getOrNull()
            syncing = false
            when {
                result?.review != null -> pendingReview = PendingSeriesReview(url, fallbackToBrowser, result.review)
                result?.seriesId != null -> {
                    selectedSeries = result.seriesId; tab = RoomLibraryTab.SERIES; reviewRefreshKey++
                    Toast.makeText(activity, "${result.name}: ${result.books} книг", Toast.LENGTH_SHORT).show()
                }
                fallbackToBrowser -> { Toast.makeText(activity, "HTTP parser не пройшов — відкриваю браузер джерел", Toast.LENGTH_LONG).show(); openSourceBrowser(url) }
                else -> Toast.makeText(activity, "Не вдалося оновити серію", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun resolveDiscoveryReview(review: SeriesMatchDecisionEntity, accept: Boolean) {
        val currentSeries = series ?: return
        if (resolvingDiscoveryReview) return
        resolvingDiscoveryReview = true
        scope.launch {
            val resolved = runCatching { SourceMetadataRepository.resolvePendingSeriesReview(activity, currentSeries.series.id, review.sourceId, review.remoteKey, accept, review.confidence) }.isSuccess
            resolvingDiscoveryReview = false; reviewRefreshKey++
            if (!resolved) Toast.makeText(activity, "Не вдалося зберегти рішення", Toast.LENGTH_LONG).show()
            else if (accept) { Toast.makeText(activity, "Джерело підтверджено — оновлюю серію", Toast.LENGTH_SHORT).show(); syncUrl(currentSeries.series.url, false) }
            else Toast.makeText(activity, "Джерело відхилено", Toast.LENGTH_SHORT).show()
        }
    }

    fun resolveBookReview(review: PendingBookReview, accept: Boolean) {
        if (resolvingBookReview) return
        resolvingBookReview = true
        scope.launch {
            val resolved = runCatching { SourceMetadataRepository.resolvePendingBookReview(activity, review, accept) }.getOrDefault(false)
            resolvingBookReview = false; reviewRefreshKey++
            when { !resolved -> Toast.makeText(activity, "Не вдалося зберегти рішення для книги", Toast.LENGTH_LONG).show(); accept -> Toast.makeText(activity, "Джерело прив’язано до книги", Toast.LENGTH_SHORT).show(); else -> Toast.makeText(activity, "Збіг книги відхилено", Toast.LENGTH_SHORT).show() }
        }
    }

    Scaffold(
        topBar = { TopAppBar(
            title = { Text(if (series != null) series.series.name else when (tab) { RoomLibraryTab.DOWNLOADS -> "Завантаження"; else -> "Audoiboo Tracker" }) },
            navigationIcon = { if (series != null) IconButton(onClick = { selectedSeries = null }) { Icon(Icons.Filled.ArrowBack, "Назад") } },
            actions = {
                if (series != null) { IconButton(onClick = { syncUrl(series.series.url, false) }, enabled = !syncing) { Icon(Icons.Filled.Refresh, "Оновити") }; IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Видалити серію") } }
                else if (tab == RoomLibraryTab.SERIES) IconButton(onClick = { addUrl = ""; showAdd = true }) { Icon(Icons.Filled.Add, "Додати серію") }
                IconButton(onClick = { activity.startActivity(Intent(activity, PlayerActivity::class.java)) }) { Icon(Icons.Filled.Headphones, "Плеєр") }
                IconButton(onClick = { openSourceBrowser() }) { Icon(Icons.Filled.Public, "Браузер джерел") }
                IconButton(onClick = { activity.startActivity(Intent(activity, CatalogDiscoveryActivity::class.java)) }) { Icon(Icons.Filled.Search, "Каталог авторів") }
                IconButton(onClick = { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }) { Icon(Icons.Filled.Settings, "Налаштування") }
            }
        ) },
        bottomBar = { if (series == null) NavigationBar {
            NavigationBarItem(tab == RoomLibraryTab.SERIES, { tab = RoomLibraryTab.SERIES }, { Icon(Icons.Filled.MenuBook, null) }, label = { Text("Серії") })
            NavigationBarItem(tab == RoomLibraryTab.BOOKS, { tab = RoomLibraryTab.BOOKS }, { Icon(Icons.Filled.LibraryBooks, null) }, label = { Text("Книги") })
            NavigationBarItem(tab == RoomLibraryTab.DOWNLOADS, { tab = RoomLibraryTab.DOWNLOADS }, { Icon(Icons.Filled.Download, null) }, label = { Text("Завантаження") })
        } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (syncing || resolvingDiscoveryReview || resolvingBookReview) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (series == null && tab != RoomLibraryTab.DOWNLOADS) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) }, label = { Text(if (tab == RoomLibraryTab.BOOKS) "Книга, автор або тег" else "Пошук серії") })
                if (tab == RoomLibraryTab.BOOKS) { Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { RoomBookFilter.entries.forEach { filter -> FilterChip(bookFilter == filter, { bookFilter = filter }, { Text(roomFilterLabel(filter)) }) } }; Spacer(Modifier.height(6.dp)) }
            }
            when {
                series != null -> RoomSeriesDetail(series, discoveryReviews, bookReviews, resolvingDiscoveryReview || resolvingBookReview, ::resolveDiscoveryReview, ::resolveBookReview)
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

    if (showAdd) AlertDialog(onDismissRequest = { showAdd = false }, title = { Text("Додати серію") }, text = { OutlinedTextField(addUrl, { addUrl = it }, label = { Text("URL серії або книги") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }, confirmButton = { TextButton(onClick = { val value = addUrl.trim(); showAdd = false; syncUrl(value, true) }, enabled = addUrl.startsWith("http")) { Text("Додати") } }, dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Скасувати") } })

    pendingReview?.let { pending ->
        val percent = (pending.review.confidence * 100).toInt()
        AlertDialog(onDismissRequest = { pendingReview = null }, title = { Text("Це та сама серія?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Нове джерело: ${pending.review.incomingName}"); Text("У бібліотеці: ${pending.review.candidateName}"); Text("Впевненість зіставлення: $percent%", style = MaterialTheme.typography.bodySmall)
            if (pending.review.evidence.isNotEmpty()) Text("Ознаки: ${pending.review.evidence.joinToString(" • ")}", style = MaterialTheme.typography.bodySmall)
            Text("Підтвердження прив’яже нове джерело до існуючої серії. Відхилення збереже рішення і не пропонуватиме цей самий збіг повторно.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { pendingReview = null; syncUrl(pending.url, pending.fallbackToBrowser, RoomSeriesReviewResolution(pending.review.candidateSeriesId, true, pending.review.confidence)) }) { Text("Так, та сама") } }, dismissButton = { TextButton(onClick = { pendingReview = null; syncUrl(pending.url, pending.fallbackToBrowser, RoomSeriesReviewResolution(pending.review.candidateSeriesId, false, pending.review.confidence)) }) { Text("Ні, окрема") } })
    }

    if (confirmDelete && series != null) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Видалити серію?") }, text = { Text("${series.series.name}\n\nЗапис серії буде видалено з бібліотеки. Завантажені аудіофайли не видаляються.") }, confirmButton = { TextButton(onClick = { val id = series.series.id; confirmDelete = false; scope.launch { LibraryRepository.deleteSeries(activity, id); selectedSeries = null } }) { Text("Видалити") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Скасувати") } })
}

@Composable
private fun RoomSeriesList(library: List<SeriesWithBooks>, onOpen: (String) -> Unit) {
    if (library.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ще немає доданих серій") }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(library, key = { it.series.id }) { item ->
            val firstCover = item.books.sortedBy { it.sortIndex }.firstOrNull()?.coverUrl
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(item.series.id) }) { Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!firstCover.isNullOrBlank()) AsyncImage(firstCover, item.series.name, Modifier.size(58.dp)) else Icon(Icons.Filled.MenuBook, null, Modifier.size(48.dp)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.series.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text("${item.books.size} книг • ${item.books.count { it.status != "READ" }} не прочитано • ${item.books.count { it.status == "NEW" }} нових", style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }
}

@Composable
private fun RoomSeriesDetail(item: SeriesWithBooks, pendingReviews: List<SeriesMatchDecisionEntity>, pendingBookReviews: List<PendingBookReview>, reviewBusy: Boolean, onResolveReview: (SeriesMatchDecisionEntity, Boolean) -> Unit, onResolveBookReview: (PendingBookReview, Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (pendingReviews.isNotEmpty()) item(key = "source-reviews") { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Rule, null); Spacer(Modifier.width(8.dp)); Text("Потрібна перевірка джерел", fontWeight = FontWeight.SemiBold) }
            pendingReviews.forEach { review -> val percent = ((review.confidence ?: 0f) * 100).toInt(); Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(review.sourceId, fontWeight = FontWeight.Medium); Text("Збіг із цією серією: $percent%", style = MaterialTheme.typography.bodySmall); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { TextButton(onClick = { onResolveReview(review, true) }, enabled = !reviewBusy) { Text("Підтвердити") }; TextButton(onClick = { onResolveReview(review, false) }, enabled = !reviewBusy) { Text("Відхилити") } } } }
        } } }
        if (pendingBookReviews.isNotEmpty()) item(key = "book-reviews") { ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Rule, null); Spacer(Modifier.width(8.dp)); Text("Потрібна перевірка книг", fontWeight = FontWeight.SemiBold) }
            pendingBookReviews.forEach { review ->
                val candidate = review.decision.candidateCanonicalBookId?.let { id -> item.books.firstOrNull { it.id == id } }; val percent = ((review.decision.confidence ?: review.source.confidence) * 100).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(review.source.remoteTitle ?: review.source.url, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${roomSourceLabel(review.source.sourceId)}${review.source.remoteOrder?.let { " • том ${formatBookOrder(it)}" }.orEmpty()} • $percent%", style = MaterialTheme.typography.bodySmall)
                    if (!review.source.remoteAuthor.isNullOrBlank()) Text(review.source.remoteAuthor, style = MaterialTheme.typography.bodySmall)
                    Text(if (candidate != null) "Пропонована книга: ${candidate.title}" else "Надійного кандидата немає — можна лише відхилити збіг", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onResolveBookReview(review, true) }, enabled = !reviewBusy && candidate != null) { Text("Прив’язати") }
                        TextButton(onClick = { onResolveBookReview(review, false) }, enabled = !reviewBusy) { Text("Відхилити") }
                        if (review.source.url.startsWith("http", ignoreCase = true)) { val context = LocalContext.current; TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(review.source.url))) }) { Text("Відкрити") } }
                    }
                }
            }
        } } }
        items(item.books.sortedBy { it.sortIndex }, key = { it.id }) { book -> RoomBookCard(book, item.series.name) }
    }
}

@Composable
private fun RoomBookCard(book: BookEntity, seriesName: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tags by remember(book.id) { mutableStateOf<List<String>>(emptyList()) }
    val sourceFlow = remember(book.id) { SourceMetadataRepository.observeSourcesForBook(context, book.id) }
    val sources by sourceFlow.collectAsState(initial = emptyList())
    var editTags by remember(book.id) { mutableStateOf(false) }
    var tagText by remember(book.id) { mutableStateOf("") }
    var resolvingArchive by remember(book.id) { mutableStateOf(false) }
    var chooseDownloadSource by remember(book.id) { mutableStateOf(false) }
    var choosePageSource by remember(book.id) { mutableStateOf(false) }
    val sourceOptions = SourcePageOptionPolicy.options(sources)
    val sourceIds = SourcePageOptionPolicy.providerIds(sources)

    LaunchedEffect(book.id, book.updatedAt) { tags = LibraryRepository.bookWithTags(context, book.id)?.tags?.map { it.name }.orEmpty() }

    fun openPage(url: String) {
        if (url.startsWith("http", ignoreCase = true)) context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) else Toast.makeText(context, "Для цього джерела немає веб-сторінки", Toast.LENGTH_SHORT).show()
    }

    fun enqueueDownload(sourceId: String?) {
        if (resolvingArchive) return
        scope.launch {
            resolvingArchive = true
            val resolvedUrls = runCatching { RoomArchiveResolver.resolveAll(context, book, sourceId) }.getOrDefault(emptyList())
            val urls = if (resolvedUrls.isNotEmpty()) resolvedUrls else if (sourceId == null) listOfNotNull(book.archiveUrl) else emptyList()
            resolvingArchive = false
            if (urls.isNotEmpty()) {
                urls.distinct().forEach { url -> ManagedDownloads.enqueue(context = context, title = book.title, series = seriesName ?: "Без серії", author = book.author, bookUrl = book.url, archiveUrl = url, fileNameHint = url) }
                Toast.makeText(context, if (urls.size == 1) "Додано до завантажень" else "Додано треків: ${urls.distinct().size}", Toast.LENGTH_SHORT).show()
            } else if (sourceId != null) Toast.makeText(context, "${roomSourceLabel(sourceId)}: аудіо не знайдено", Toast.LENGTH_LONG).show()
            else { Toast.makeText(context, "Плагін не знайшов аудіо — відкриваю браузер джерел", Toast.LENGTH_LONG).show(); context.startActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_URL, book.url)) }
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (!book.coverUrl.isNullOrBlank()) AsyncImage(book.coverUrl, book.title, Modifier.width(58.dp).height(82.dp)) else Icon(Icons.Filled.MenuBook, null, Modifier.size(48.dp)); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!seriesName.isNullOrBlank()) Text(seriesName, style = MaterialTheme.typography.bodySmall)
            if (!book.author.isNullOrBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
            if (sourceIds.isNotEmpty()) Text("${if (sourceIds.size == 1) "Джерело" else "Джерела"}: ${sourceIds.joinToString(" • ") { roomSourceLabel(it) }}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { AssistChip(onClick = { scope.launch { LibraryRepository.updateBookStatus(context, book.id, nextRoomStatus(book.status)) } }, label = { Text(roomStatusLabel(book.status)) }); AssistChip(onClick = { tagText = tags.joinToString(", "); editTags = true }, leadingIcon = { Icon(Icons.Filled.Label, null) }, label = { Text(if (tags.isEmpty()) "Теги" else tags.joinToString(" • "), maxLines = 1) }) }
        }
        Column {
            IconButton(onClick = { if (sourceOptions.size > 1) choosePageSource = true else openPage(sourceOptions.singleOrNull()?.url ?: book.url) }) { Icon(Icons.Filled.OpenInBrowser, "Сторінка") }
            IconButton(onClick = { if (sourceIds.size > 1) chooseDownloadSource = true else enqueueDownload(sourceIds.singleOrNull()) }, enabled = !resolvingArchive) { if (resolvingArchive) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(if (book.archiveUrl.isNullOrBlank()) Icons.Filled.Link else Icons.Filled.CloudDownload, if (book.archiveUrl.isNullOrBlank()) "Знайти аудіо" else "Завантажити") }
        }
    } }

    if (choosePageSource) AlertDialog(onDismissRequest = { choosePageSource = false }, title = { Text("Яке джерело відкрити?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sourceOptions.forEach { source ->
            val label = if (SourcePageOptionPolicy.needsObservationHint(source, sourceOptions)) "${roomSourceLabel(source.sourceId)} — ${SourcePageOptionPolicy.observationHint(source)}" else roomSourceLabel(source.sourceId)
            TextButton(onClick = { choosePageSource = false; openPage(source.url) }, modifier = Modifier.fillMaxWidth()) { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosePageSource = false }) { Text("Скасувати") } })

    if (chooseDownloadSource) AlertDialog(onDismissRequest = { chooseDownloadSource = false }, title = { Text("Звідки завантажити?") }, text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { chooseDownloadSource = false; enqueueDownload(null) }, modifier = Modifier.fillMaxWidth()) { Text("Автоматично — найкраще доступне") }
        sourceIds.forEach { sourceId -> TextButton(onClick = { chooseDownloadSource = false; enqueueDownload(sourceId) }, modifier = Modifier.fillMaxWidth()) { Text(roomSourceLabel(sourceId)) } }
    } }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseDownloadSource = false }) { Text("Скасувати") } })

    if (editTags) AlertDialog(onDismissRequest = { editTags = false }, title = { Text("Теги: ${book.title}") }, text = { OutlinedTextField(tagText, { tagText = it }, label = { Text("Через кому") }) }, confirmButton = { TextButton(onClick = { val values = tagText.split(',').map { it.trim() }.filter { it.isNotBlank() }; scope.launch { LibraryRepository.setBookTags(context, book.id, values); tags = LibraryRepository.bookWithTags(context, book.id)?.tags?.map { it.name }.orEmpty(); editTags = false } }) { Text("Зберегти") } }, dismissButton = { TextButton(onClick = { editTags = false }) { Text("Скасувати") } })
}

private fun formatBookOrder(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
private fun roomSourceLabel(sourceId: String): String = when (sourceId) { "audioboo" -> "Audioboo"; "baza-knig" -> "Baza-Knig"; "knigavuhe" -> "Knigavuhe"; "poleknig" -> "Poleknig"; "lis10book" -> "Lis10book"; "izib" -> "Izib/PDA"; else -> sourceId }
private fun roomFilterLabel(filter: RoomBookFilter): String = when (filter) { RoomBookFilter.ALL -> "Усі"; RoomBookFilter.NEW -> "Нові"; RoomBookFilter.READING -> "Читаю"; RoomBookFilter.READ -> "Прочитані"; RoomBookFilter.TAGGED -> "З тегами"; RoomBookFilter.UNTAGGED -> "Без тегів" }
private fun nextRoomStatus(status: String): String = when (status.uppercase()) { "NEW" -> "UNREAD"; "UNREAD" -> "READING"; "READING" -> "READ"; else -> "UNREAD" }
private fun roomStatusLabel(status: String): String = when (status.uppercase()) { "NEW" -> "Нова"; "UNREAD" -> "Не прочитано"; "READING" -> "Читаю"; "READ" -> "Прочитано"; else -> status }
