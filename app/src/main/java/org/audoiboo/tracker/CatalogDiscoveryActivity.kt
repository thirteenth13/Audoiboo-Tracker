package org.audoiboo.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.audoiboo.tracker.plugin.*

class CatalogDiscoveryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { CatalogDiscoveryScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogDiscoveryScreen(activity: ComponentActivity) {
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<CatalogSourceMatch>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        val value = query.trim()
        if (value.isBlank() || loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { CatalogSourceBridge(BuiltInSourcePluginManager.registry).discoverByAuthor(value) }
                .onSuccess { results = it }
                .onFailure { error = it.message ?: "Не вдалося виконати пошук" }
            loading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Каталог авторів") },
                navigationIcon = { IconButton(onClick = activity::finish) { Icon(Icons.Filled.ArrowBack, "Назад") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                label = { Text("Автор") },
                placeholder = { Text("Наприклад: Роман Прокофьев") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { TextButton(onClick = ::search, enabled = query.isNotBlank() && !loading) { Text("Знайти") } },
                singleLine = true
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) }
            if (!loading && error == null && results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "Введіть ім’я автора, щоб знайти його серії" else "Серій не знайдено")
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(results, key = { it.canonical.id }) { result -> CatalogSeriesCard(result) }
                }
            }
        }
    }
}

@Composable
private fun CatalogSeriesCard(result: CatalogSourceMatch) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.series.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(result.author.name, style = MaterialTheme.typography.bodyMedium)
            val accepted = result.sources.count { it.match.disposition == MatchDisposition.AUTO_ACCEPT }
            val review = result.sources.count { it.match.disposition == MatchDisposition.REVIEW }
            Text(
                when {
                    accepted > 0 -> "Аудіоджерела: $accepted знайдено" + if (review > 0) " • $review потребує перевірки" else ""
                    review > 0 -> "Аудіоджерела: $review потребує перевірки"
                    else -> "Аудіоджерела поки не знайдені"
                },
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider()
            result.series.books.forEach { book ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(book.seriesNumber?.let { formatSeriesNumber(it) } ?: "—", modifier = Modifier.width(42.dp), fontWeight = FontWeight.Medium)
                    Column(Modifier.weight(1f)) {
                        Text(book.title)
                        book.firstPublishYear?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (result.sources.isNotEmpty()) {
                HorizontalDivider()
                result.sources.forEach { finding ->
                    val percent = (finding.match.confidence * 100).toInt()
                    val status = when (finding.match.disposition) {
                        MatchDisposition.AUTO_ACCEPT -> "збіг"
                        MatchDisposition.REVIEW -> "перевірити"
                        MatchDisposition.REJECT -> "відхилено"
                    }
                    Text("${finding.plugin.descriptor.name}: $percent% • $status", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatSeriesNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
