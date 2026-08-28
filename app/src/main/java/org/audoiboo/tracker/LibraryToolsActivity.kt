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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class ToolBook(val dir: String, val title: String, val series: String?, val tracks: List<PlayerLibraryItem>)

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
    var query by remember { mutableStateOf("") }
    var editBook by remember { mutableStateOf<ToolBook?>(null) }
    var tagText by remember { mutableStateOf("") }
    val visible = books.filter { b ->
        query.isBlank() || b.title.contains(query, true) || b.series?.contains(query, true) == true ||
            PlayerExtras.tags(activity, b.dir).any { it.contains(query, true) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Інструменти бібліотеки") }, navigationIcon = { IconButton({ activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Пошук книги, серії або тегу") }, singleLine = true)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { shareBookmarks(activity) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("Поділитися закладками") }
                OutlinedButton(onClick = { copyBookmarks(activity) }, modifier = Modifier.weight(1f)) { Text("Копіювати Markdown") }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visible, key = { it.dir }) { b ->
                    val tags = PlayerExtras.tags(activity, b.dir)
                    ElevatedCard(Modifier.fillMaxWidth().clickable { editBook = b; tagText = tags.joinToString(", ") }) {
                        Row(Modifier.padding(14.dp).fillMaxWidth()) {
                            Icon(Icons.Filled.Label, null)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.title, fontWeight = FontWeight.SemiBold)
                                if (!b.series.isNullOrBlank()) Text(b.series, style = MaterialTheme.typography.bodySmall)
                                Text(if (tags.isEmpty()) "Без тегів" else tags.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
                PlayerExtras.setTags(activity, b.dir, tagText.split(',').map { it.trim() }.filter { it.isNotBlank() })
                editBook = null
                Toast.makeText(activity, "Теги збережено", Toast.LENGTH_SHORT).show()
            }) { Text("Зберегти") } },
            dismissButton = { TextButton(onClick = { editBook = null }) { Text("Скасувати") } }
        )
    }
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

private fun bookmarksMarkdown(context: Context): String {
    val library = PlayerLibrary.all(context).associateBy { it.uri }
    val bookmarks = PlayerExtras.bookmarks(context)
    if (bookmarks.isEmpty()) return "# Audoiboo Tracker — закладки\n\nЗакладок немає."
    return buildString {
        append("# Audoiboo Tracker — закладки\n\n")
        bookmarks.sortedByDescending { it.createdAt }.forEach { b ->
            val item = library[b.uri]
            val book = item?.bookTitle ?: item?.relativePath?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/') ?: "Аудіокнига"
            val series = item?.series?.takeIf { it.isNotBlank() }
            append("- **").append(book).append("**")
            if (series != null) append(" — ").append(series)
            append(" — ").append(formatToolTime(b.position))
            if (b.note.isNotBlank()) append(" — ").append(b.note.replace('\n', ' '))
            append('\n')
        }
    }
}

private fun copyBookmarks(context: Context) {
    val text = bookmarksMarkdown(context)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Audoiboo bookmarks", text))
    Toast.makeText(context, "Markdown скопійовано", Toast.LENGTH_SHORT).show()
}

private fun shareBookmarks(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_SUBJECT, "Audoiboo Tracker — закладки")
        putExtra(Intent.EXTRA_TEXT, bookmarksMarkdown(context))
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
