package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
internal fun ManagedDownloadsScreen(context: Context) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        ManagedDownloads.initialize(context)
        while (true) {
            delay(1000)
            tick++
        }
    }
    val records = remember(tick) { ManagedDownloads.list(context) }
    val hasActive = records.any { it.state !in setOf(ManagedDownloadState.COMPLETED, ManagedDownloadState.CANCELLED) }
    val hasHistory = records.any { it.state in setOf(ManagedDownloadState.COMPLETED, ManagedDownloadState.CANCELLED, ManagedDownloadState.FAILED) }

    Column(Modifier.fillMaxSize()) {
        if (records.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { ManagedDownloads.cancelAll(context); tick++ },
                    enabled = hasActive,
                    modifier = Modifier.weight(1f)
                ) { Text("Зупинити всі") }
                OutlinedButton(
                    onClick = { ManagedDownloads.clearHistory(context); tick++ },
                    enabled = hasHistory,
                    modifier = Modifier.weight(1f)
                ) { Text("Очистити історію") }
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Тут з’являться завантаження аудіокниг.")
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(records, key = { it.id }) { r ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(listOfNotNull(r.author, r.series).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                            }
                            when (r.state) {
                                ManagedDownloadState.DOWNLOADING, ManagedDownloadState.QUEUED ->
                                    IconButton(onClick = { ManagedDownloads.pause(context, r.id) }) { Icon(Icons.Filled.Pause, "Пауза") }
                                ManagedDownloadState.PAUSED, ManagedDownloadState.FAILED ->
                                    IconButton(onClick = { ManagedDownloads.resume(context, r.id) }) { Icon(Icons.Filled.PlayArrow, "Продовжити") }
                                ManagedDownloadState.COMPLETED -> IconButton(onClick = {
                                    val dir = DownloadDestinationPolicy.bookRelativeDir(r)
                                    context.startActivity(
                                        Intent(context, PlayerActivity::class.java)
                                            .putExtra("relativeDir", dir)
                                            .putExtra("title", r.title)
                                    )
                                }) { Icon(Icons.Filled.Headphones, "Відкрити в плеєрі") }
                                else -> Unit
                            }
                            if (r.state !in listOf(ManagedDownloadState.COMPLETED, ManagedDownloadState.CANCELLED)) {
                                IconButton(onClick = { ManagedDownloads.cancel(context, r.id) }) { Icon(Icons.Filled.Cancel, "Скасувати") }
                            }
                            if (r.state == ManagedDownloadState.CANCELLED) {
                                IconButton(onClick = { ManagedDownloads.remove(context, r.id); tick++ }) { Icon(Icons.Filled.Delete, "Видалити") }
                            }
                        }
                        val progress = if (r.total > 0) r.downloaded.toFloat() / r.total else 0f
                        if (r.state in listOf(ManagedDownloadState.DOWNLOADING, ManagedDownloadState.PAUSED, ManagedDownloadState.EXTRACTING)) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        }
                        Text(stateLabel(r), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        r.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        val root = StorageAccess.displayName(context)?.let { "$it/" } ?: "Downloads/"
                        val bookPath = DownloadDestinationPolicy.bookRelativeDir(r)
                        val displayPath = if (DownloadDestinationPolicy.shouldExtract(r.fileName, AppPrefs.unpack(context))) {
                            "$root$bookPath/"
                        } else "$root$bookPath/${r.fileName}"
                        Text(displayPath, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun stateLabel(r: ManagedDownloadRecord): String {
    val pct = if (r.total > 0) " ${(r.downloaded * 100 / r.total)}%" else ""
    return when (r.state) {
        ManagedDownloadState.QUEUED -> "Очікує умов мережі"
        ManagedDownloadState.DOWNLOADING -> "Завантажується$pct"
        ManagedDownloadState.PAUSED -> "Пауза$pct"
        ManagedDownloadState.EXTRACTING -> "Перевіряється та розпаковується"
        ManagedDownloadState.COMPLETED -> "Готово"
        ManagedDownloadState.FAILED -> "Помилка — буде повторна спроба"
        ManagedDownloadState.CANCELLED -> "Скасовано"
    }
}
