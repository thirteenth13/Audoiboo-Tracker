package org.audoiboo.tracker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
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
                Text("Серія перевіряється швидко: повний список книг, bookLookup для перших двох позицій і лише один реальний WebView mediaCapture. Якщо він порожній, raw-probe показує URL до фільтрації, джерело, host/extension, причину відхилення, activation і timings.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class DiagnosticMediaDetail(
    val static: List<DownloadCandidate>,
    val captured: PluginMediaCaptureResult?,
    val elapsedMs: Long
)

internal fun diagnosticMediaVerdict(
    capturedCount: Int,
    activatedTracks: Int?,
    rawTotal: Int?,
    rawAccepted: Int?,
    activateCount: Int?
): String = when {
    capturedCount > 0 && activatedTracks != null && activatedTracks > capturedCount -> "FOUND_TRACK_MISMATCH"
    capturedCount > 0 -> "FOUND"
    rawTotal == null -> "NO_RAW_PROBE"
    rawTotal > 0 && (rawAccepted ?: 0) > 0 -> "RAW_ACCEPTED_BUT_CAPTURE_MISSED"
    rawTotal > 0 -> "RAW_FOUND_BUT_FILTERED"
    activateCount == 0 || activatedTracks == 0 -> "PLAYER_NOT_ACTIVATED_OR_NO_TRACKS"
    else -> "NO_RAW_MEDIA"
}

private suspend fun diagnosePlugin(pluginId: String, url: String): String {
    val registration = PluginPackageRuntime.registrations.firstOrNull { it.packageId == pluginId }
        ?: return "plugin=$pluginId\nERROR: плагін не встановлено"
    val plugin = PluginPackageRuntime.registry.byId(pluginId)
    val lines = mutableListOf<String>()
    val reportStarted = SystemClock.elapsedRealtime()
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
        val started = SystemClock.elapsedRealtime()
        val result = runCatching { block() }
        val elapsed = SystemClock.elapsedRealtime() - started
        lines += if (result.isSuccess) "$name: ${result.getOrThrow()} | ${elapsed}ms" else "$name: ERROR ${result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName} | ${elapsed}ms"
    }

    suspend fun mediaDetail(book: SourceBook): DiagnosticMediaDetail {
        val started = SystemClock.elapsedRealtime()
        val static = if (plugin is DownloadResolver) runCatching { plugin.resolveDownloads(book) }.getOrElse { emptyList() } else emptyList()
        val captured = runCatching { DeviceWebViewResolutionRuntime.captureDiagnostics(book.url) }.getOrNull()
        return DiagnosticMediaDetail(static, captured, SystemClock.elapsedRealtime() - started)
    }

    fun activatedTracks(captured: PluginMediaCaptureResult?): Int? = captured?.diagnostics
        ?.mapNotNull { Regex("(?:js:)?tracks=(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        ?.maxOrNull()

    fun appendRawProbe(probe: RawMediaProbeResult, indent: String) {
        val accepted = probe.candidates.count { it.accepted }
        lines += "${indent}rawProbe=${probe.candidates.size} accepted=$accepted filtered=${probe.candidates.size - accepted}"
        lines += "${indent}rawTiming: loaded=${probe.loadedMs ?: -1}ms firstCandidate=${probe.firstCandidateMs ?: -1}ms total=${probe.elapsedMs}ms activate=${probe.activateCount ?: -1}"
        probe.candidates.take(10).forEach {
            lines += "${indent}rawCandidate [${it.filter}] source=${it.sources.joinToString("+")} host=${it.host} ext=${it.extension ?: "-"}: ${it.url.take(240)}"
        }
        probe.diagnostics.takeLast(10).forEach { lines += "${indent}raw-event: ${it.take(180)}" }
    }

    suspend fun appendMediaCheck(book: SourceBook, indent: String) {
        val detail = mediaDetail(book)
        val tracks = activatedTracks(detail.captured)
        val probe = if (detail.captured != null && detail.captured.mediaUrls.isEmpty()) runCatching { RawMediaProbeRuntime.probe(book.url) }.getOrNull() else null
        val capturedCount = detail.captured?.mediaUrls?.size ?: 0
        val verdict = diagnosticMediaVerdict(
            capturedCount = capturedCount,
            activatedTracks = tracks,
            rawTotal = probe?.candidates?.size,
            rawAccepted = probe?.candidates?.count { it.accepted },
            activateCount = probe?.activateCount
        )
        lines += "${indent}media-check: staticMedia=${detail.static.size} capturedMedia=$capturedCount tracks=${tracks ?: -1} elapsed=${detail.elapsedMs}ms verdict=$verdict${if (detail.captured == null) " capture=unsupported" else ""}"
        detail.static.take(5).forEach { lines += "${indent}  static ${it.type}: ${it.url.take(180)}" }
        detail.captured?.mediaUrls?.take(8)?.forEach { lines += "${indent}  captured: ${it.take(220)}" }
        detail.captured?.diagnostics?.takeLast(12)?.forEach { lines += "${indent}  capture-event: ${it.take(180)}" }
        if (probe != null) appendRawProbe(probe, "$indent  ")
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
            val refs = series!!.books
            lines += "books-detail: total=${refs.size}"
            val samples = mutableListOf<SourceBook>()
            refs.forEachIndexed { index, ref ->
                lines += "  ${index + 1}. ${ref.title?.take(100) ?: "?"}"
                lines += "     url=${ref.url}"
                if (index < 2 && plugin is BookProvider) {
                    val book = runCatching { plugin.loadBook(ref.url) }.getOrNull()
                    lines += if (book == null) "     bookLookup=null" else "     bookLookup=ok title=${book.title.take(100)} author=${book.authors.joinToString { it.name }.take(100)}"
                    if (book != null) samples += book
                } else {
                    lines += "     metadata-only"
                }
            }
            val mediaBook = samples.firstOrNull()
            if (mediaBook != null) {
                lines += "media-sample: first-valid-book"
                lines += "  title=${mediaBook.title.take(120)}"
                lines += "  url=${mediaBook.url}"
                appendMediaCheck(mediaBook, "  ")
            } else {
                lines += "media-sample: skipped: no valid book among first 2 refs"
            }
            "listed=${refs.size} bookLookupChecked=${minOf(2, refs.size)} mediaChecked=${if (mediaBook != null) 1 else 0}"
        }
    } else if (rootBook != null) {
        stage("mediaResolution") {
            appendMediaCheck(rootBook!!, "  ")
            "checked=1"
        }
    } else {
        lines += "mediaResolution: skipped: no book resolved"
    }

    val snapshot = PluginDiagnostics.snapshot(pluginId)
    if (snapshot.entries.isNotEmpty()) {
        lines += "runtime-events:"
        snapshot.entries.takeLast(30).forEach { lines += "  $it" }
    }
    lines += "report-total=${SystemClock.elapsedRealtime() - reportStarted}ms"
    return lines.joinToString("\n")
}
