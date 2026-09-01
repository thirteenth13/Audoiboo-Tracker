package org.audoiboo.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.*

class PluginDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginPackageRuntime.initialize(filesDir)
        setContent { AudoibooTheme(this) { PluginDiagnosticsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginDiagnosticsScreen(activity: ComponentActivity) {
    val plugins = remember { PluginPackageRuntime.registrations.filter { it.origin == PluginOrigin.PACKAGE } }
    var selectedId by remember { mutableStateOf(activity.intent.getStringExtra("pluginId") ?: plugins.firstOrNull()?.packageId.orEmpty()) }
    var url by remember { mutableStateOf(activity.intent.getStringExtra("url").orEmpty()) }
    var report by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun copy() {
        val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Audoiboo plugin diagnostics", report))
        Toast.makeText(activity, "Діагностику скопійовано", Toast.LENGTH_SHORT).show()
    }

    fun runDiagnostics() {
        if (busy || selectedId.isBlank() || url.isBlank()) return
        scope.launch {
            busy = true
            report = withContext(Dispatchers.IO) { diagnosePlugin(selectedId, url.trim()) }
            busy = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Діагностика плагіна") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(plugins.firstOrNull { it.packageId == selectedId }?.let { "${it.displayName} v${it.descriptor?.version ?: "?"}" } ?: "Оберіть плагін")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    plugins.forEach { p -> DropdownMenuItem(text = { Text("${p.displayName} v${p.descriptor?.version ?: "?"}") }, onClick = { selectedId = p.packageId; menu = false }) }
                }
            }
            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL книги або серії") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Button(onClick = ::runDiagnostics, enabled = !busy && selectedId.isNotBlank() && url.startsWith("http"), modifier = Modifier.fillMaxWidth()) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "Перевірка…" else "Запустити діагностику")
            }
            if (report.isNotBlank()) {
                OutlinedButton(onClick = ::copy, modifier = Modifier.fillMaxWidth()) { Text("Скопіювати звіт") }
                Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Перевірка покаже підтримку URL, стан плагіна, bookLookup, seriesLookup, кількість книг, downloadResolution та останні runtime-події. Вміст сторінок і cookies у звіт не записуються.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private suspend fun diagnosePlugin(pluginId: String, url: String): String {
    val registration = PluginPackageRuntime.registrations.firstOrNull { it.packageId == pluginId }
        ?: return "plugin=$pluginId\nERROR: плагін не встановлено"
    val plugin = PluginPackageRuntime.registry.byId(pluginId)
    val lines = mutableListOf<String>()
    lines += "plugin=$pluginId"
    lines += "version=${registration.descriptor?.version ?: "?"} api=${registration.descriptor?.apiVersion ?: "?"} state=${registration.state}"
    lines += "url=$url"
    lines += "hosts=${registration.descriptor?.hosts?.sorted()?.joinToString() ?: "?"}"
    lines += "capabilities=${registration.descriptor?.capabilities?.map { it.name }?.sorted()?.joinToString() ?: "?"}"
    if (plugin == null) {
        lines += "ERROR: плагін не активований у runtime"
        return lines.joinToString("\n")
    }
    lines += "supports=${runCatching { plugin.supports(url) }.getOrDefault(false)}"

    suspend fun stage(name: String, block: suspend () -> String) {
        val result = runCatching { block() }
        lines += if (result.isSuccess) "$name: ${result.getOrThrow()}" else "$name: ERROR ${result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName}"
    }

    if (plugin is BookProvider) stage("bookLookup") {
        val book = plugin.loadBook(url)
        if (book == null) "null" else "title=${book.title.take(120)} | author=${book.authors.joinToString { it.name }.take(120)} | series=${book.seriesTitle ?: "-"}"
    }
    if (plugin is SeriesProvider) stage("seriesLookup") {
        val series = plugin.resolveSeries(url)
        if (series == null) "null" else "title=${series.title.take(120)} | seriesUrl=${series.url} | books=${series.books.size}"
    }
    if (plugin is DownloadResolver) stage("downloadResolution") {
        val seed = if (plugin is BookProvider) plugin.loadBook(url) else null
        if (seed == null) "skipped: bookLookup=null" else {
            val downloads = plugin.resolveDownloads(seed)
            "media=${downloads.size}" + downloads.take(5).joinToString(prefix = " | ") { it.url.take(100) }
        }
    }
    val snapshot = PluginDiagnostics.snapshot(pluginId)
    if (snapshot.entries.isNotEmpty()) {
        lines += "runtime-events:"
        snapshot.entries.takeLast(20).forEach { lines += "  $it" }
    }
    return lines.joinToString("\n")
}