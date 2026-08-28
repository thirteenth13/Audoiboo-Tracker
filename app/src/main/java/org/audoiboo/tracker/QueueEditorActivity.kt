package org.audoiboo.tracker

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import kotlin.math.abs

private data class QueueBookInfo(val dir: String, val title: String, val series: String?)

private object QueueEditorStore {
    private const val PREFS = "player_queue"
    private const val KEY = "book_dirs"

    fun load(context: Context): List<String> = runCatching {
        val arr = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    fun save(context: Context, dirs: List<String>) {
        val arr = JSONArray(); dirs.distinct().forEach(arr::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

class QueueEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { QueueEditorScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueEditorScreen(activity: ComponentActivity) {
    val library = remember { queueBookInfo(activity) }
    var queue by remember { mutableStateOf(QueueEditorStore.load(activity)) }
    val activeDir = remember { PlayerExtras.resume(activity)?.dir }

    fun persist(value: List<String>) {
        queue = value
        QueueEditorStore.save(activity, value)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Черга прослуховування") },
                navigationIcon = { IconButton({ activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (queue.isNotEmpty()) TextButton(onClick = { persist(emptyList()) }) { Text("Очистити") }
                }
            )
        }
    ) { padding ->
        if (queue.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Черга порожня")
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                Text(
                    "Утримуй ⋮⋮ і тягни книгу вгору або вниз. Порядок зберігається одразу.",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(queue, key = { _, dir -> dir }) { index, dir ->
                        val info = library[dir] ?: QueueBookInfo(dir, dir.substringAfterLast('/'), null)
                        DraggableQueueRow(
                            info = info,
                            active = dir == activeDir,
                            canUp = index > 0,
                            canDown = index < queue.lastIndex,
                            onMoveUp = { if (index > 0) persist(moveQueue(queue, index, index - 1)) },
                            onMoveDown = { if (index < queue.lastIndex) persist(moveQueue(queue, index, index + 1)) }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DraggableQueueRow(
    info: QueueBookInfo,
    active: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var accumulated by remember(info.dir) { mutableFloatStateOf(0f) }
    val thresholdPx = 48f
    ListItem(
        headlineContent = { Text(info.title) },
        supportingContent = {
            Column {
                if (!info.series.isNullOrBlank()) Text(info.series)
                if (active) Text("Зараз слухається", color = MaterialTheme.colorScheme.primary)
            }
        },
        leadingContent = { if (active) Icon(Icons.Filled.PlayArrow, null) },
        trailingContent = {
            Icon(
                Icons.Filled.DragHandle,
                "Перетягнути",
                Modifier
                    .size(42.dp)
                    .padding(8.dp)
                    .pointerInput(info.dir, canUp, canDown) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { accumulated = 0f },
                            onDragCancel = { accumulated = 0f },
                            onDragEnd = { accumulated = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                accumulated += amount.y
                                if (abs(accumulated) >= thresholdPx) {
                                    if (accumulated < 0f && canUp) onMoveUp()
                                    else if (accumulated > 0f && canDown) onMoveDown()
                                    accumulated = 0f
                                }
                            }
                        )
                    }
            )
        }
    )
}

private fun moveQueue(items: List<String>, from: Int, to: Int): List<String> {
    if (from !in items.indices || to !in items.indices || from == to) return items
    return items.toMutableList().apply { add(to, removeAt(from)) }
}

private fun queueBookInfo(context: Context): Map<String, QueueBookInfo> = PlayerLibrary.all(context)
    .groupBy { it.relativePath.replace('\\', '/').trimEnd('/') }
    .mapValues { (dir, tracks) ->
        val first = tracks.first()
        QueueBookInfo(
            dir,
            first.bookTitle?.takeIf { it.isNotBlank() } ?: dir.substringAfterLast('/').ifBlank { "Книга" },
            first.series
        )
    }
