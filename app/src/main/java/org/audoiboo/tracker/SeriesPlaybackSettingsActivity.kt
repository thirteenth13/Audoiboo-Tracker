package org.audoiboo.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

class SeriesPlaybackSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { SeriesPlaybackSettingsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeriesPlaybackSettingsScreen(activity: ComponentActivity) {
    val series = remember {
        PlayerLibrary.all(activity).mapNotNull { it.series?.takeIf(String::isNotBlank) }.distinct().sortedBy { it.lowercase() }
    }
    var revision by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Швидкість за серіями") },
                navigationIcon = { IconButton({ activity.finish() }) { Icon(Icons.Filled.ArrowBack, "Назад") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "Це швидкість за замовчуванням. Якщо для окремої книги вже вибрана власна швидкість, вона має пріоритет.",
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall
            )
            if (series.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Завантажених серій ще немає") }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(series, key = { it }) { name ->
                        val current = remember(name, revision) { PlayerExtras.seriesSpeedFor(activity, name) }
                        var menu by remember(name) { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(name) },
                            supportingContent = { Text("За замовчуванням ${String.format(Locale.US, "%.2fx", current)}") },
                            trailingContent = {
                                Box {
                                    OutlinedButton(onClick = { menu = true }) { Text(String.format(Locale.US, "%.2fx", current)) }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        listOf(.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { speed ->
                                            DropdownMenuItem(
                                                text = { Text(String.format(Locale.US, "%.2fx", speed)) },
                                                onClick = {
                                                    PlayerExtras.setSeriesSpeed(activity, name, speed)
                                                    revision++
                                                    menu = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
