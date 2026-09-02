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
        DeviceWebViewResolutionRuntime.initialize(this)
        RawMediaProbeRuntime.initialize(this)
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
                Text(if (busy) "WebView перевірка…" else "Запустити діагностику")
            }
            if (report.isNotBlank()) {
                OutlinedButton(onClick = ::copy, modifier = Modifier.fillMaxWidth()) { Text("Скопіювати звіт") }
                Text(report, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Для кожної книги звіт окремо показує staticMedia з HTML resolver та capturedMedia з того самого WebView mediaCapture, який застосунок використовує для завантаження. Якщо capture не знаходить медіа, перша книга серії додатково показує сирі audio-кандидати до фільтрації плагіном.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private suspend fun diagnosePlugin(pluginId: String, url: String): String {
    val registration = PluginPackageRuntime.registrations.firstOrNull { it.packageId == pluginId }
        ?: return "plugin=$pluginId\nERROR: плагін не встановлено"
    val plugin = PluginPackageRuntime.registry.byId(pluginId)
    val lines = mutableListOf<String>()
    lines += "app=${BuildProvenance.label}"
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

    suspend fun mediaDetail(book: SourceBook): Pair<List<DownloadCandidate>, PluginMediaCaptureResult?> {
        val static = if (plugin is DownloadResolver) runCatching { plugin.resolveDownloads(book) }.getOrElse { emptyList() } else emptyList()
        val captured = runCatching { DeviceWebViewResolutionRuntime.captureDiagnostics(book.url) }.getOrNull()
        return static to captured
    }

    suspend fun appendRawProbe(bookUrl: String, indent: String) {
        val probe = runCatching { RawMediaProbeRuntime.probe(bookUrl) }.getOrNull() ?: return
        lines += "${indent}rawProbe=${probe.candidates.size}"
        probe.candidates.take(8).forEach { lines += "${indent}rawCandidate: ${it.take(220)}" }
        probe.diagnostics.takeLast(6).forEach { lines += "${indent}raw-event: ${it.take(160)}" }
    }

    var rootBook: SourceBook? = null
    if (plugin is BookProvider) stage("bookLookup") {
        val book = plugin.loadBook(url)
        rootBook = book
        if (book == null) "null" else "title=${book.title.take(120)} | author=${book.authors.joinToString { it.name }.take(120)} | series=${book.seriesTitle ?: "-"}"
    }

    var series: SourceSeries? = null
    if (plugin is SeriesProvider) stage("seriesLookup") {
        val resolved = plugin.resolveSeries(url)
        series = resolved
        if (resolved == null) "null" else "title=${resolved.title.take(120)} | seriesUrl=${resolved.url} | books=${resolved.books.size}"
    }

    if (series != null && plugin is SeriesProvider) {
        stage("seriesBooks") {
            val loaded = plugin.loadSeriesBooks(series!!)
            val refs = if (loaded.isNotEmpty()) loaded.map { it.url to it } else series!!.books.map { it.url to null }
            lines += "books-detail: total=${refs.size}"
            refs.forEachIndexed { index, (bookUrl, prefetched) ->
                val book = prefetched ?: if (plugin is BookProvider) runCatching { plugin.loadBook(bookUrl) }.getOrNull() else null
                val title = book?.title?.take(90) ?: series!!.books.getOrNull(index)?.title?.take(90) ?: "?"
                lines += "  ${index + 1}. $title"
                lines += "     url=$bookUrl"
                if (book == null) {
                    lines += "     bookLookup=null staticMedia=0 capturedMedia=0"
                } else {
                    val (static, captured) = mediaDetail(book)
                    lines += "     bookLookup=ok staticMedia=${static.size} capturedMedia=${captured?.mediaUrls?.size ?: 0}${if (captured == null) " capture=unsupported" else ""}"
                    static.take(3).forEach { lines += "       static ${it.type}: ${it.url.take(150)}" }
                    captured?.mediaUrls?.take(3)?.forEach { lines += "       captured: ${it.take(150)}" }
                    captured?.diagnostics?.takeLast(8)?.forEach { lines += "       capture-event: ${it.take(150)}" }
                    if (index == 0 && captured != null && captured.mediaUrls.isEmpty()) appendRawProbe(bookUrl, "       ")
                }
            }
            "checked=${refs.size}"
        }
    } else if (rootBook != null) {
        stage("mediaResolution") {
            val (static, captured) = mediaDetail(rootBook!!)
            lines += "media-detail: staticMedia=${static.size} capturedMedia=${captured?.mediaUrls?.size ?: 0}${if (captured == null) " capture=unsupported" else ""}"
            static.take(5).forEach { lines += "  static ${it.type}: ${it.url.take(150)}" }
            captured?.mediaUrls?.take(5)?.forEach { lines += "  captured: ${it.take(150)}" }
            captured?.diagnostics?.takeLast(12)?.forEach { lines += "  capture-event: ${it.take(150)}" }
            if (captured != null && captured.mediaUrls.isEmpty()) appendRawProbe(rootBook!!.url, "  ")
            "checked=1"
        }
    } else {
        lines += "mediaResolution: skipped: no book resolved"
    }

    val snapshot = PluginDiagnostics.snapshot(pluginId)
    if (snapshot.entries.isNotEmpty()) {
        lines += "runtime-events:"
        snapshot.entries.takeLast(40).forEach { lines += "  $it" }
    }
    return lines.joinToString("\n")
}
