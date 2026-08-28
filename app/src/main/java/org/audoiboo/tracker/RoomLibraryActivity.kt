package org.audoiboo.tracker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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

/** Room-native library UI used during the staged migration away from tracker/library JSON. */
class RoomLibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { RoomLibraryScreen(this) } }
    }
}

private enum class RoomLibraryTab { SERIES, BOOKS, DOWNLOADS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomLibraryScreen(activity: ComponentActivity) {
    var tab by remember { mutableStateOf(RoomLibraryTab.SERIES) }
    var query by remember { mutableStateOf("") }
    var selectedSeries by remember { mutableStateOf<String?>(null) }
    val library by LibraryRepository.observe(activity).collectAsState(initial = emptyList())
    val pagingFlow = remember(query) { LibraryRepository.pagedBooks(activity, query) }
    val paged = pagingFlow.collectAsLazyPagingItems()
    val series = library.firstOrNull { it.series.id == selectedSeries }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (series != null) series.series.name else when (tab) { RoomLibraryTab.DOWNLOADS -> "Завантаження"; else -> "Audoiboo Tracker" }) },
                navigationIcon = {
                    if (series != null) IconButton(onClick = { selectedSeries = null }) { Icon(Icons.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = { activity.startActivity(Intent(activity, PlayerActivity::class.java)) }) { Icon(Icons.Filled.Headphones, "Плеєр") }
                    IconButton(onClick = { activity.startActivity(Intent(activity, MainActivity::class.java)) }) { Icon(Icons.Filled.Public, "Audioboo браузер і керування") }
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
            if (series == null && tab != RoomLibraryTab.DOWNLOADS) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    label = { Text(if (tab == RoomLibraryTab.BOOKS) "Книга, автор або тег" else "Пошук серії") }
                )
            }
            when {
                series != null -> RoomSeriesDetail(series)
                tab == RoomLibraryTab.SERIES -> RoomSeriesList(library.filter { query.isBlank() || it.series.name.contains(query, true) }, onOpen = { selectedSeries = it })
                tab == RoomLibraryTab.DOWNLOADS -> ManagedDownloadsScreen(activity)
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(paged.itemCount) { index -> paged[index]?.let { RoomBookCard(it, seriesName = library.firstOrNull { s -> s.series.id == it.seriesId }?.series?.name) } }
                    if (paged.loadState.refresh is androidx.paging.LoadState.Loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    if (paged.loadState.append is androidx.paging.LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    val error = (paged.loadState.refresh as? androidx.paging.LoadState.Error)?.error ?: (paged.loadState.append as? androidx.paging.LoadState.Error)?.error
                    if (error != null) item { Text("Помилка Room/Paging: ${error.message}", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun RoomSeriesList(library: List<SeriesWithBooks>, onOpen: (String) -> Unit) {
    if (library.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Ще немає доданих серій") }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(library, key = { it.series.id }) { item ->
            val firstCover = item.books.sortedBy { it.sortIndex }.firstOrNull()?.coverUrl
            ElevatedCard(Modifier.fillMaxWidth().clickable { onOpen(item.series.id) }) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!firstCover.isNullOrBlank()) AsyncImage(firstCover, item.series.name, Modifier.size(58.dp))
                    else Icon(Icons.Filled.MenuBook, null, Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.series.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${item.books.size} книг • ${item.books.count { it.status != "READ" }} не прочитано • ${item.books.count { it.status == "NEW" }} нових", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomSeriesDetail(item: SeriesWithBooks) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    LaunchedEffect(book.id) { tags = LibraryRepository.bookWithTags(context, book.id)?.tags?.map { it.name }.orEmpty() }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!book.coverUrl.isNullOrBlank()) AsyncImage(book.coverUrl, book.title, Modifier.width(58.dp).height(82.dp))
            else Icon(Icons.Filled.MenuBook, null, Modifier.size(48.dp))
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
                if (!book.archiveUrl.isNullOrBlank()) IconButton(onClick = { ManagedDownloads.enqueue(context, book.title, seriesName ?: "Без серії", book.author, book.url, book.archiveUrl) }) { Icon(Icons.Filled.CloudDownload, "Завантажити") }
            }
        }
    }

    if (editTags) AlertDialog(
        onDismissRequest = { editTags = false },
        title = { Text("Теги: ${book.title}") },
        text = { OutlinedTextField(tagText, { tagText = it }, label = { Text("Через кому") }) },
        confirmButton = { TextButton(onClick = {
            val values = tagText.split(',').map { it.trim() }.filter { it.isNotBlank() }
            scope.launch {
                LibraryRepository.setBookTags(context, book.id, values)
                tags = LibraryRepository.bookWithTags(context, book.id)?.tags?.map { it.name }.orEmpty()
                editTags = false
            }
        }) { Text("Зберегти") } },
        dismissButton = { TextButton(onClick = { editTags = false }) { Text("Скасувати") } }
    )
}

private fun nextRoomStatus(status: String): String = when (status.uppercase()) {
    "NEW" -> "UNREAD"
    "UNREAD" -> "READING"
    "READING" -> "READ"
    else -> "UNREAD"
}

private fun roomStatusLabel(status: String): String = when (status.uppercase()) {
    "NEW" -> "Нова"
    "UNREAD" -> "Не прочитано"
    "READING" -> "Читаю"
    "READ" -> "Прочитано"
    else -> status
}
