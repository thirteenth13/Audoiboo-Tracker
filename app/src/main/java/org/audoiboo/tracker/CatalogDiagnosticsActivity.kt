package org.audoiboo.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.CatalogBookSearchEngine
import org.audoiboo.tracker.plugin.CatalogDiscoveryEngine
import org.audoiboo.tracker.plugin.CatalogProviderDiagnostics
import org.audoiboo.tracker.plugin.PluginPackageRuntime

class CatalogDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AudoibooTheme(this) { CatalogDiagnosticsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogDiagnosticsScreen(activity: ComponentActivity) {
    var author by remember { mutableStateOf("Роман Прокофьев") }
    var book by remember { mutableStateOf("Звездная кровь") }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun runDiagnostics() {
        if (running) return
        running = true
        report = "Перевірка каталогу…"
        scope.launch {
            report = withContext(Dispatchers.IO) { diagnoseCatalog(author, book) }
            running = false
        }
    }

    fun copyReport() {
        if (report.isBlank()) return
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Audoiboo catalog diagnostics", report))
        Toast.makeText(activity, "Звіт каталогу скопійовано", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Діагностика каталогу") },
                navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Перевіряє той самий пошук, який виконується на екрані «Каталог», і окремо показує стан Open Library, FantLab та Google Books.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("Автор") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = book,
                onValueChange = { book = it },
                label = { Text("Книга / назва серії") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = ::runDiagnostics,
                enabled = !running && (author.isNotBlank() || book.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (running) "Перевіряється…" else "Запустити діагностику") }

            if (report.isNotBlank()) {
                OutlinedButton(onClick = ::copyReport, modifier = Modifier.fillMaxWidth()) { Text("Скопіювати звіт") }
                Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private suspend fun diagnoseCatalog(authorQuery: String, bookQuery: String): String = supervisorScope {
    val author = authorQuery.trim().ifBlank { "Роман Прокофьев" }
    val book = bookQuery.trim().ifBlank { "Звездная кровь" }
    val registry = PluginPackageRuntime.registry
    val started = SystemClock.elapsedRealtime()
    val lines = mutableListOf<String>()

    lines += "catalog-diagnostics"
    lines += "author=$author"
    lines += "book=$book"

    val authorStarted = SystemClock.elapsedRealtime()
    val authorTask = async {
        runCatching { CatalogDiscoveryEngine(registry).discoverByAuthor(author) }
    }
    val bookStarted = SystemClock.elapsedRealtime()
    val bookTask = async {
        runCatching { CatalogBookSearchEngine(registry).search(book) }
    }
    val providersTask = async {
        runCatching { CatalogProviderDiagnostics.run(registry, author, book) }
    }

    val authorResult = authorTask.await()
    val authorElapsed = SystemClock.elapsedRealtime() - authorStarted
    authorResult.onSuccess { catalogs ->
        val seriesCount = catalogs.sumOf { it.series.size }
        val booksCount = catalogs.sumOf { catalog -> catalog.series.sumOf { it.books.size } }
        lines += "federated-author: catalogs=${catalogs.size} series=$seriesCount books=$booksCount elapsed=${authorElapsed}ms"
        catalogs.take(8).forEach { catalog ->
            lines += "  ${catalog.providerId}: author=${catalog.author.name} series=${catalog.series.size}"
        }
    }.onFailure { error ->
        lines += "federated-author: ERROR=${error.javaClass.simpleName}:${error.message.orEmpty().take(180)} elapsed=${authorElapsed}ms"
    }

    val bookResult = bookTask.await()
    val bookElapsed = SystemClock.elapsedRealtime() - bookStarted
    bookResult.onSuccess { hits ->
        lines += "federated-book: hits=${hits.size} elapsed=${bookElapsed}ms"
        hits.take(12).forEach { hit ->
            lines += "  ${hit.book.providerId}: ${hit.book.title} | ${hit.book.authors.joinToString()} | confidence=${"%.2f".format(hit.confidence)}"
        }
    }.onFailure { error ->
        lines += "federated-book: ERROR=${error.javaClass.simpleName}:${error.message.orEmpty().take(180)} elapsed=${bookElapsed}ms"
    }

    lines += "providers:"
    providersTask.await().onSuccess { results ->
        results.forEach { result ->
            val http = "http(author=${result.authorHttp ?: -1},book=${result.bookHttp ?: -1})"
            val error = result.error?.let { " ERROR=$it" }.orEmpty()
            lines += "  ${result.providerId}: $http authors=${result.authors} books=${result.books} elapsed=${result.elapsedMs}ms$error"
        }
    }.onFailure { error ->
        lines += "  ERROR=${error.javaClass.simpleName}:${error.message.orEmpty().take(180)}"
    }

    lines += "total=${SystemClock.elapsedRealtime() - started}ms"
    lines.joinToString("\n")
}
