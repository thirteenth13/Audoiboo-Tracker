package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.*
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class PluginManagementActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PluginPackageRuntime.initialize(filesDir)
        setContent { AudoibooTheme(this) { PluginManagementScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginManagementScreen(context: Context) {
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var catalogLoaded by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var updates by remember { mutableStateOf<List<PluginUpdate>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val registrations = remember(refresh) { PluginPackageRuntime.registrations }
    val quarantinedIds = remember(refresh) { PluginPackageRuntime.quarantinedPackageIds }
    val visibleIds = remember(registrations, quarantinedIds) { (registrations.filter { it.origin == PluginOrigin.PACKAGE }.map { it.packageId } + quarantinedIds).distinct().sorted() }
    val updatesById = updates.associateBy { it.entry.id }

    fun reload() { PluginPackageRuntime.reload(); refresh++ }
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    fun runOperation(block: suspend () -> Boolean, success: String, failure: String) {
        if (busy) return
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { runCatching { block() }.getOrDefault(false) }
            reload(); busy = false; toast(if (ok) success else failure)
        }
    }
    fun installUpdate(update: PluginUpdate) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { PluginUpdateService.install(context, update.entry) }.getOrNull() }
            reload(); busy = false
            toast(if (result?.installed == true) "Плагін ${update.entry.name} оновлено" else "Не вдалося оновити ${update.entry.name}: ${result?.message ?: "помилка"}")
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !busy) scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "plugin-${UUID.randomUUID()}.abplugin")
                    context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { output -> if (!copyBounded(input, output, PluginArchiveLimits.MAX_ARCHIVE_BYTES)) error("Пакет завеликий") } } ?: error("Не вдалося відкрити файл")
                    try { PluginPackageRuntime.installFromFile(tmp) } finally { tmp.delete() }
                }.getOrElse { PluginInstallResult(false, null, it.message ?: "Помилка імпорту") }
            }
            reload(); busy = false; toast(if (result.installed) "Плагін встановлено" else "Помилка встановлення: ${result.message}")
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Плагіни джерел") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Імпортувати .abplugin") }
            OutlinedButton(onClick = {
                if (!busy) scope.launch {
                    busy = true
                    val result = withContext(Dispatchers.IO) { PluginUpdateService.check(context) }
                    catalogLoaded = true
                    catalog = result.catalog
                    updates = result.updates
                    busy = false
                    result.error?.let(::toast)
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Оновити каталог плагінів") }
            if (busy) CircularProgressIndicator()
            catalog.filter { entry -> registrations.none { it.packageId == entry.id } }.forEach { entry ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${entry.id} • v${entry.version} • API ${entry.apiVersion}", style = MaterialTheme.typography.bodySmall)
                    entry.description?.let { Text(it) }
                    Button(onClick = { installUpdate(PluginUpdate(entry, null)) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Встановити") }
                } }
            }
            if (catalogLoaded && catalog.isNotEmpty() && visibleIds.isEmpty()) Text("У каталозі немає нових плагінів")
            if (visibleIds.isNotEmpty()) Text("Встановлені плагіни", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            visibleIds.forEach { pluginId ->
                val registration = registrations.firstOrNull { it.packageId == pluginId }
                PluginCard(registration, pluginId, pluginId in quarantinedIds, busy, updatesById[pluginId],
                    onUpdate = ::installUpdate,
                    onDiagnostics = { context.startActivity(Intent(context, PluginDiagnosticsActivity::class.java).putExtra("pluginId", pluginId)) },
                    onToggle = { enabled -> runOperation({ if (enabled) PluginPackageRuntime.enablePackage(pluginId) else PluginPackageRuntime.disablePackage(pluginId) }, if (enabled) "Плагін увімкнено" else "Плагін вимкнено", "Не вдалося змінити стан") },
                    onQuarantine = { runOperation({ PluginPackageRuntime.quarantinePackage(pluginId) }, "Плагін переміщено в карантин", "Не вдалося перемістити") },
                    onRestore = { runOperation({ PluginPackageRuntime.restorePackage(pluginId) }, "Плагін відновлено", "Немає версії для відновлення") },
                    onRollback = { runOperation({ PluginPackageRuntime.rollbackPackage(pluginId) }, "Повернуто попередню версію", "Попередньої версії немає") })
            }
        }
    }
}

@Composable
private fun PluginCard(registration: SourcePluginRegistration?, pluginId: String, hasQuarantine: Boolean, busy: Boolean, update: PluginUpdate?, onUpdate: (PluginUpdate) -> Unit, onDiagnostics: () -> Unit, onToggle: (Boolean) -> Unit, onQuarantine: () -> Unit, onRestore: () -> Unit, onRollback: () -> Unit) {
    val descriptor = registration?.descriptor; val manifest = registration?.manifest; val enabled = registration?.state == PluginState.ENABLED
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) { Text(registration?.displayName ?: pluginId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(pluginId, style = MaterialTheme.typography.bodySmall) }
            if (registration != null) Switch(checked = enabled, onCheckedChange = onToggle, enabled = !busy && registration.state !in setOf(PluginState.QUARANTINED, PluginState.INCOMPATIBLE))
        }
        Text("Стан: ${registration?.state?.name ?: "QUARANTINE_ONLY"}", style = MaterialTheme.typography.bodySmall)
        descriptor?.let { Text("Версія: ${it.version} • API: ${it.apiVersion}", style = MaterialTheme.typography.bodySmall); Text("Хости: ${it.hosts.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall); Text("Можливості: ${it.capabilities.map { c -> c.name }.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall) }
        manifest?.permissions?.let { Text("Мережа: ${it.networkHosts.sorted().joinToString().ifBlank { "немає" }}", style = MaterialTheme.typography.bodySmall) }
        registration?.failureReason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (update != null) Button(onClick = { onUpdate(update) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Оновити до v${update.entry.version}") }
        OutlinedButton(onClick = onDiagnostics, enabled = !busy && registration != null, modifier = Modifier.fillMaxWidth()) { Text("Діагностика") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onRollback, enabled = !busy && registration != null, modifier = Modifier.weight(1f)) { Text("Rollback") }; OutlinedButton(onClick = onQuarantine, enabled = !busy && registration != null, modifier = Modifier.weight(1f)) { Text("Карантин") } }
        if (hasQuarantine) OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Відновити з карантину") }
    } }
}

private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Boolean { val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0L; while (true) { val read = input.read(buffer); if (read < 0) return true; total += read; if (total > maxBytes) return false; output.write(buffer, 0, read) } }