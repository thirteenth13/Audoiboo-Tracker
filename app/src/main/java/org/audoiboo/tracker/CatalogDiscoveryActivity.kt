package org.audoiboo.tracker

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
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

private enum class CatalogSearchMode { AUTHOR, BOOK }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogDiscoveryScreen(activity: ComponentActivity) {
    var mode by remember { mutableStateOf(CatalogSearchMode.AUTHOR) }
    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var importingId by remember { mutableStateOf<String?>(null) }
    var resolvingBookId by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<CatalogSourceMatch>>(emptyList()) }
    var bookHits by remember { mutableStateOf<List<CatalogBookSearchHit>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var cacheHit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun resetForMode(newMode: CatalogSearchMode) {
        if (mode == newMode) return
        mode = newMode
        searched = false
        results = emptyList()
        bookHits = emptyList()
        error = null
        cacheHit = false
    }

    fun openSource(url: String) {
        activity.startActivity(Intent(activity, MainActivity::class.java).apply { putExtra(MainActivity.EXTRA_URL, url) })
    }

    fun search(forceRefresh: Boolean = false) {
        val value = query.trim()
        if (value.isBlank() || loading || resolvingBookId != null) return
        searched = true
        error = null
        results = emptyList()
        when (mode) {
            CatalogSearchMode.AUTHOR -> {
                val cache = CatalogSearchCaches.authorDiscovery
                if (!forceRefresh) {
                    cache.get(value)?.takeIf { it.isNotEmpty() }?.let {
                        results = it
                        bookHits = emptyList()
                        cacheHit = true
                        return
                    }
                } else cache.invalidate(value)
                loading = true
                cacheHit = false
                scope.launch {
                    runCatching { CatalogSourceBridge(PluginPackageRuntime.registry).discoverByAuthor(value) }
                        .onSuccess {
                            results = it
                            bookHits = emptyList()
                            if (it.isNotEmpty()) cache.put(value, it) else cache.invalidate(value)
                        }
                        .onFailure { error = it.message ?: "Не вдалося виконати пошук" }
                    loading = false
                }
            }
            CatalogSearchMode.BOOK -> {
                val cache = CatalogSearchCaches.bookSearch
                if (!forceRefresh) {
                    cache.get(value)?.takeIf { it.isNotEmpty() }?.let {
                        bookHits = it
                        cacheHit = true
                        return
                    }
                } else cache.invalidate(value)
                loading = true
                cacheHit = false
                scope.launch {
                    runCatching { CatalogBookSearchEngine(PluginPackageRuntime.registry).search(value) }
                        .onSuccess {
                            bookHits = it
                            if (it.isNotEmpty()) cache.put(value, it) else cache.invalidate(value)
                        }
                        .onFailure { error = it.message ?: "Не вдалося знайти книгу" }
                    loading = false
                }
            }
        }
    }

    fun resolveBook(hit: CatalogBookSearchHit) {
        if (resolvingBookId != null || loading) return
        resolvingBookId = "${hit.book.providerId}:${hit.book.remoteId}"
        error = null
        scope.launch {
            runCatching { CatalogSourceBridge(PluginPackageRuntime.registry).discoverByBook(hit) }
                .onSuccess { results = it }
                .onFailure { error = it.message ?: "Не вдалося знайти аудіоджерела" }
            resolvingBookId = null
        }
    }

    fun addToLibrary(result: CatalogSourceMatch, preferredSourceId: String? = null) {
        if (importingId != null) return
        importingId = result.canonical.id
        scope.launch {
            when (val imported = runCatching { CatalogLibraryImport.add(activity, result, preferredSourceId) }.getOrNull()) {
                is CatalogLibraryImportResult.Added -> {
                    Toast.makeText(activity, "${imported.name}: додано ${imported.books} книг", Toast.LENGTH_LONG).show()
                    activity.startActivity(Intent(activity, RoomLibraryActivity::class.java))
                }
                is CatalogLibraryImportResult.NeedsReview -> {
                    Toast.makeText(activity, "Збіг потребує перевірки — відкриваю джерело", Toast.LENGTH_LONG).show()
                    openSource(imported.sourceUrl)
                }
                CatalogLibraryImportResult.NoAudioSource, null ->
                    Toast.makeText(activity, "Не вдалося додати вибране аудіоджерело", Toast.LENGTH_LONG).show()
            }
            importingId = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Каталог") },
            navigationIcon = { IconButton(onClick = activity::finish) { Icon(Icons.Filled.ArrowBack, "Назад") } }
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = mode == CatalogSearchMode.AUTHOR, onClick = { resetForMode(CatalogSearchMode.AUTHOR) }, label = { Text("Автор") })
                FilterChip(selected = mode == CatalogSearchMode.BOOK, onClick = { resetForMode(CatalogSearchMode.BOOK) }, label = { Text("Книга") })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                label = { Text(if (mode == CatalogSearchMode.AUTHOR) "Автор" else "Назва книги") },
                placeholder = { Text(if (mode == CatalogSearchMode.AUTHOR) "Наприклад: Роман Прокофьев" else "Наприклад: Звездная кровь 10") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { TextButton(onClick = { search(false) }, enabled = query.isNotBlank() && !loading && resolvingBookId == null) { Text("Знайти") } },
                singleLine = true
            )
            if (loading || importingId != null || resolvingBookId != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (cacheHit && (results.isNotEmpty() || bookHits.isNotEmpty())) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Показано кешований результат", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { search(true) }, enabled = !loading && resolvingBookId == null) { Text("Оновити") }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }

            if (mode == CatalogSearchMode.BOOK && results.isNotEmpty() && bookHits.isNotEmpty()) {
                TextButton(onClick = { results = emptyList() }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text("← Повернутися до знайдених книг")
                }
            }

            when {
                results.isNotEmpty() -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(results, key = { it.canonical.id }) { result ->
                        CatalogSeriesCard(
                            result = result,
                            importing = importingId == result.canonical.id,
                            actionsEnabled = importingId == null && resolvingBookId == null,
                            onAdd = { sourceId -> addToLibrary(result, sourceId) },
                            onOpenSource = ::openSource
                        )
                    }
                }
                mode == CatalogSearchMode.BOOK && bookHits.isNotEmpty() -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(bookHits, key = { "${it.book.providerId}:${it.book.remoteId}" }) { hit ->
                        CatalogBookHitCard(hit, resolvingBookId == "${hit.book.providerId}:${hit.book.remoteId}", resolvingBookId == null, ::resolveBook)
                    }
                }
                !loading && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !searched && mode == CatalogSearchMode.AUTHOR -> "Введіть ім’я автора, щоб знайти його серії"
                            !searched -> "Введіть назву книги, щоб знайти автора, серію та аудіоджерела"
                            mode == CatalogSearchMode.AUTHOR -> "Серій не знайдено"
                            else -> "Книг не знайдено"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogBookHitCard(
    hit: CatalogBookSearchHit,
    resolving: Boolean,
    enabled: Boolean,
    onResolve: (CatalogBookSearchHit) -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(hit.book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (hit.book.authors.isNotEmpty()) Text(hit.book.authors.joinToString(), style = MaterialTheme.typography.bodyMedium)
            hit.book.seriesTitles.firstOrNull()?.let { series ->
                Text("Серія: $series${hit.book.seriesNumber?.let { " • №${formatSeriesNumber(it)}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
            }
            hit.book.firstPublishYear?.let { Text("Рік: $it", style = MaterialTheme.typography.bodySmall) }
            Text("${hit.book.providerId} • ${(hit.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onResolve(hit) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (resolving) "Шукаю аудіо…" else "Знайти аудіо")
            }
        }
    }
}

@Composable
private fun CatalogSeriesCard(
    result: CatalogSourceMatch,
    importing: Boolean,
    actionsEnabled: Boolean,
    onAdd: (String?) -> Unit,
    onOpenSource: (String) -> Unit
) {
    val bookAvailability = remember(result) { CatalogBookAvailabilityResolver.resolve(result) }
    val rankedSources = remember(result) { CatalogAudioSourceSelector.rank(result) }
    val best = rankedSources.firstOrNull()

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(result.series.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (result.author.name.isNotBlank()) Text(result.author.name, style = MaterialTheme.typography.bodyMedium)
            val accepted = result.sources.count { it.disposition == MatchDisposition.AUTO_ACCEPT }
            val review = result.sources.count { it.disposition == MatchDisposition.REVIEW }
            val availableBooks = bookAvailability.count { availability -> availability.sources.any { it.disposition == MatchDisposition.AUTO_ACCEPT } }
            Text(
                when {
                    accepted > 0 -> "Аудіоджерела: $accepted знайдено" + if (review > 0) " • $review потребує перевірки" else ""
                    review > 0 -> "Аудіоджерела: $review потребує перевірки"
                    else -> "Аудіоджерела поки не знайдені"
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (result.series.books.isNotEmpty()) Text("Книги з підтвердженим аудіо: $availableBooks/${result.series.books.size}", style = MaterialTheme.typography.bodySmall)

            best?.let { candidate ->
                if (!candidate.isComplete) {
                    Text(
                        "Найкраще джерело неповне: ${candidate.matchedBooks}/${candidate.totalBooks} книг. Відсутні книги залишаться поза імпортом, доки не знайдеться інше джерело.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                Button(onClick = { onAdd(null) }, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth()) {
                    Text(if (importing) "Додаю…" else "Додати найкраще джерело (${candidate.matchedBooks}/${candidate.totalBooks})")
                }
            }

            if (rankedSources.size > 1) {
                Text("Вибрати аудіоджерело", fontWeight = FontWeight.Medium)
                rankedSources.forEach { candidate ->
                    val finding = candidate.finding
                    val percent = (finding.confidence * 100).toInt()
                    OutlinedButton(onClick = { onAdd(finding.sourceId) }, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth()) {
                        Text("${finding.sourceId} • ${candidate.matchedBooks}/${candidate.totalBooks} книг • $percent%")
                    }
                }
            }

            HorizontalDivider()
            bookAvailability.forEach { availability ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    val book = availability.catalogBook
                    Text(book.seriesNumber?.let(::formatSeriesNumber) ?: "—", modifier = Modifier.width(42.dp), fontWeight = FontWeight.Medium)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(book.title)
                        book.firstPublishYear?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall) }
                        val auto = availability.sources.filter { it.disposition == MatchDisposition.AUTO_ACCEPT }
                        val pending = availability.sources.filter { it.disposition == MatchDisposition.REVIEW }
                        when {
                            auto.isNotEmpty() -> Text("Аудіо: ${auto.map { it.sourceId }.distinct().joinToString()}", style = MaterialTheme.typography.bodySmall)
                            pending.isNotEmpty() -> Text("Аудіо: можливий збіг — потрібна перевірка", style = MaterialTheme.typography.bodySmall)
                            else -> Text("Аудіо не знайдено", style = MaterialTheme.typography.bodySmall)
                        }
                        availability.sources.take(3).forEach { source ->
                            val percent = (source.confidence * 100).toInt()
                            TextButton(onClick = { onOpenSource(source.sourceBook.url) }, enabled = actionsEnabled, contentPadding = PaddingValues(0.dp)) {
                                Text("${source.sourceId} • $percent% • відкрити")
                            }
                        }
                    }
                }
            }
            if (result.sources.isNotEmpty()) {
                HorizontalDivider()
                result.sources.forEach { finding ->
                    val percent = (finding.confidence * 100).toInt()
                    val status = when (finding.disposition) {
                        MatchDisposition.AUTO_ACCEPT -> "збіг"
                        MatchDisposition.REVIEW -> "перевірити"
                        MatchDisposition.REJECT -> "відхилено"
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${finding.sourceId}: $percent% • $status", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onOpenSource(finding.series.url) }, enabled = actionsEnabled) { Text("Відкрити серію") }
                    }
                }
            }
        }
    }
}

private fun formatSeriesNumber(value: Double): String = if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()