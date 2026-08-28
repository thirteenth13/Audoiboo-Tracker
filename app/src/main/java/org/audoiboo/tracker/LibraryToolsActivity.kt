package org.audoiboo.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONArray

private data class ToolBook(val dir: String, val title: String, val series: String?, val tracks: List<PlayerLibraryItem>)
private enum class SmartList { ALL, STARTED, NOT_STARTED, RECENT, TAGGED, UNTAGGED }

private object ToolQueue {
    private const val PREFS = "player_queue"
    private const val KEY = "book_dirs"
    fun load(context: Context): List<String> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())
    fun save(context: Context, dirs: List<String>) {
        val a = JSONArray(); dirs.distinct().forEach(a::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }
}

class LibraryToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { LibraryToolsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryToolsScreen(activity: ComponentActivity) {
    val books = remember { toolBooks(activity) }
    val scope = rememberCoroutineScope()
    val roomHistory by PlayerExtrasRepository.observeHistory(activity).collectAsState(initial = emptyList())
    val roomBookmarks by PlayerExtrasRepository.observeBookmarks(activity).collectAsState(initial = emptyList())
    var roomTags by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var query by remember { mutableStateOf("") }
    var smartList by remember { mutableStateOf(SmartList.ALL) }
    var editBook by remember { mutableStateOf<ToolBook?>(null) }
    var tagText by remember { mutableStateOf("") }
    var tagRevision by remember { mutableIntStateOf(0) }
    var queueRevision by remember { mutableIntStateOf(0) }

    LaunchedEffect(books, tagRevision) {
        roomTags = RoomTagSync.tagsForDirs(activity, books.map { it.dir })
    }

    val recentDirs = remember(roomHistory) { roomHistory.take(20).map { it.dir }.toSet() }
    val queue = remember(queueRevision) { ToolQueue.load(activity) }
    val activeDir = PlayerExtras.resume(activity)?.dir
    val activeBook = books.firstOrNull { it.dir == activeDir }
    val currentSeriesDirs = activeBook?.series?.let { series -> books.filter { it.series == series }.map { it.dir } } ?: listOfNotNull(activeDir)
    val visible = remember(books, query, smartList, roomTags, recentDirs) {
        books.filter { b ->
            val tags = roomTags[b.dir].orEmpty()
            val started = b.tracks.any { PlayerPrefs.position(activity, android.net.Uri.parse(it.uri)) > 0L }
            val smart = when (smartList) {
                SmartList.ALL -> true
                SmartList.STARTED -> started
                SmartList.NOT_STARTED -> !started
                SmartList.RECENT -> b.dir in recentDirs
                SmartList.TAGGED -> tags.isNotEmpty()
                SmartList.UNTAGGED -> tags.isEmpty()
            }
            smart && (query.isBlank() || b.title.contains(query, true) || b.series?.contains(query, true) == true || tags.any { it.contains(query, true) })
        }
    }
    val markdown = remember(roomBookmarks) { bookmarksMarkdown(activity, roomBookmarks) }

    fun saveQueue(value: List<String>, message: String) {
        ToolQueue.save(activity, value)
        queueRevision++
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Інструменти бібліотеки") }, navigationIcon = { IconButton({ activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Пошук книги, серії або тегу") }, singleLine = true)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartList.entries.forEach { item ->
                    FilterChip(selected = smartList == item, onClick = { smartList = item }, label = { Text(smartListLabel(item)) })
                }
            }
            Text("${visible.size} із ${books.size} книг • у черзі ${queue.size}", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareBookmarks(activity, markdown) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Поділитися закладками") }
                OutlinedButton(onClick = { copyBookmarks(activity, markdown) }, modifier = Modifier.weight(1f)) { Text("Копіювати Markdown") }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.dir }) { b ->
                    val tags = roomTags[b.dir].orEmpty()
                    var menu by remember(b.dir) { mutableStateOf(false) }
                    ElevatedCard(Modifier.fillMaxWidth().clickable { editBook = b; tagText = tags.joinToString(", ") }) {
                        Row(Modifier.padding(14.dp).fillMaxWidth()) {
                            Icon(Icons.Filled.Label, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, fontWeight = FontWeight.SemiBold)
                                if (!b.series.isNullOrBlank()) Text(b.series, style = MaterialTheme.typography.bodySmall)
                                Text(if (tags.isEmpty()) "Без тегів" else tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Черга") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Відтворити наступною") }, onClick = { menu = false; saveQueue(PlayerQueueActions.playNext(queue, activeDir, b.dir), "Додано наступною") }, leadingIcon = { Icon(Icons.Filled.SkipNext, null) })
                                    DropdownMenuItem(text = { Text("Після поточної серії") }, onClick = { menu = false; saveQueue(PlayerQueueActions.afterSeries(queue, activeDir, currentSeriesDirs, b.dir), "Додано після поточної серії") }, enabled = activeDir != null, leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) })
                                    DropdownMenuItem(text = { Text("Додати в кінець черги") }, onClick = { menu = false; saveQueue(queue + b.dir, "Додано в чергу") }, leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editBook?.let { b ->
        AlertDialog(
            onDismissRequest = { editBook = null },
            title = { Text("Теги: ${b.title}") },
            text = { OutlinedTextField(tagText, { tagText = it }, label = { Text("Через кому") }, supportingText = { Text("Наприклад: улюблене, робота, LitRPG") }) },
            confirmButton = { TextButton(onClick = {
                val values = tagText.split(',').map { it.trim() }.filter { it.isNotBlank() }
                scope.launch {
                    val saved = RoomTagSync.setTagsForDir(activity, b.dir, values)
                    if (saved) {
                        // Compatibility mirror until every legacy reader is retired.
                        PlayerExtras.setTags(activity, b.dir, values)
                        tagRevision++
                        editBook = null
                        Toast.makeText(activity, "Теги збережено", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(activity, "Не вдалося зіставити книгу з Room", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Зберегти") } },
            dismissButton = { TextButton(onClick = { editBook = null }) { Text("Скасувати") } }
        )
    }
}

private fun smartListLabel(value: SmartList): String = when (value) {
    SmartList.ALL -> "Усі"
    SmartList.STARTED -> "Розпочаті"
    SmartList.NOT_STARTED -> "Не початі"
    SmartList.RECENT -> "Нещодавні"
    SmartList.TAGGED -> "З тегами"
    SmartList.UNTAGGED -> "Без тегів"
}

private fun toolBooks(context: Context): List<ToolBook> = PlayerLibrary.all(context)
    .groupBy { it.relativePath.replace('\\', '/').trimEnd('/') }
    .map { (dir, tracks) ->
        val first = tracks.first()
        ToolBook(
            dir = dir,
            title = first.bookTitle?.takeIf { it.isNotBlank() } ?: dir.substringAfterLast('/').ifBlank { "Книга" },
            series = first.series,
            tracks = tracks
        )
    }.sortedWith(compareBy<ToolBook> { it.series ?: "~" }.thenBy { PlayerLogic.parseBookNumber(it.title) ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })

private fun bookmarksMarkdown(context: Context, bookmarks: List<PlayerBookmarkEntity>): String {
    val library = PlayerLibrary.all(context).associateBy { it.uri }
    if (bookmarks.isEmpty()) return "# Audoiboo Tracker — закладки\n\nЗакладок немає."
    return buildString {
        append("# Audoiboo Tracker — закладки\n\n")
        bookmarks.sortedByDescending { it.createdAt }.forEach { b ->
            val item = library[b.uri]
            val book = item?.bookTitle ?: item?.relativePath?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/') ?: "Аудіокнига"
            val series = item?.series?.takeIf { it.isNotBlank() }
            append("- **").append(book).append("**")
            if (series != null) append(" — ").append(series)
            append(" — ").append(formatToolTime(b.positionMs))
            if (b.note.isNotBlank()) append(" — ").append(b.note.replace('\n', ' '))
            append('\n')
        }
    }
}

private fun copyBookmarks(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Audoiboo bookmarks", text))
    Toast.makeText(context, "Markdown скопійовано", Toast.LENGTH_SHORT).show()
}

private fun shareBookmarks(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, "Audoiboo Tracker — закладки")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Поділитися закладками"))
}

private fun formatToolTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
