package org.audoiboo.tracker

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
private fun PluginManagementScreen(activity: ComponentActivity) {
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var updates by remember { mutableStateOf<List<PluginUpdate>>(emptyList()) }
    var installable by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var catalogLoaded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val maxImportBytes = remember { PluginArchiveLimits().maxCompressedBytes }
    val updateService = remember { PluginUpdateService() }
    fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    fun refresh() { revision++ }
    fun runOperation(block: () -> Boolean, success: String, failure: String) {
        if (busy) return
        scope.launch { busy = true; val ok = withContext(Dispatchers.IO) { runCatching(block).getOrDefault(false) }; busy = false; toast(if (ok) success else failure); refresh() }
    }
    fun checkCatalog() {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { updateService.check(PluginPackageRuntime.registrations) }
            busy = false
            when (result) {
                is PluginUpdateCheckResult.Success -> { updates = result.updates; installable = result.installable; catalogLoaded = true; toast(if (updates.isEmpty() && installable.isEmpty()) "Каталог перевірено — оновлень немає" else "Нових: ${installable.size} • оновлень: ${updates.size}") }
                is PluginUpdateCheckResult.Failed -> toast("Не вдалося завантажити каталог: ${result.reason}")
            }
        }
    }
    fun installEntry(entry: PluginCatalogEntry) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                val f = updateService.downloadVerified(entry, activity.cacheDir).getOrElse { return@withContext PluginInstallResult.Failed(it.message ?: "Помилка завантаження", it) }
                try { PluginPackageRuntime.installPackage(f) } finally { f.delete() }
            }
            busy = false
            when (result) { is PluginInstallResult.Installed -> { installable = installable.filterNot { it.id == entry.id }; toast("${result.registration.displayName} встановлено") }; is PluginInstallResult.Rejected -> toast("Плагін відхилено: ${result.reason}"); is PluginInstallResult.Failed -> toast("Помилка встановлення: ${result.reason}") }
            refresh()
        }
    }
    fun installUpdate(update: PluginUpdate) {
        if (busy) return
        scope.launch {
            busy = true
            val wasEnabled = PluginPackageRuntime.registrations.firstOrNull { it.packageId == update.entry.id }?.state == PluginState.ENABLED
            val result = withContext(Dispatchers.IO) {
                val f = updateService.downloadVerified(update, activity.cacheDir).getOrElse { return@withContext PluginInstallResult.Failed(it.message ?: "Помилка завантаження", it) }
                try { PluginPackageRuntime.installPackage(f).also { if (it is PluginInstallResult.Installed && wasEnabled) PluginPackageRuntime.enablePackage(update.entry.id) } } finally { f.delete() }
            }
            busy = false
            when (result) { is PluginInstallResult.Installed -> { updates = updates.filterNot { it.entry.id == update.entry.id }; toast("${result.registration.displayName} оновлено") }; is PluginInstallResult.Rejected -> toast("Оновлення відхилено: ${result.reason}"); is PluginInstallResult.Failed -> toast("Помилка оновлення: ${result.reason}") }
            refresh()
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                val temp = File(activity.cacheDir, "plugin-import-${UUID.randomUUID()}.abplugin")
                try { val copied = activity.contentResolver.openInputStream(uri)?.use { i -> temp.outputStream().use { o -> copyBounded(i, o, maxImportBytes) } } ?: false; if (!copied) PluginInstallResult.Rejected("Файл завеликий або не читається") else PluginPackageRuntime.installPackage(temp) } catch (t: Throwable) { PluginInstallResult.Failed(t.message ?: "Помилка імпорту", t) } finally { temp.delete() }
            }
            busy = false
            toast(when (result) { is PluginInstallResult.Installed -> "${result.registration.displayName} встановлено"; is PluginInstallResult.Rejected -> "Плагін відхилено: ${result.reason}"; is PluginInstallResult.Failed -> "Помилка встановлення: ${result.reason}" })
            refresh()
        }
    }
    val registrations = revision.let { PluginPackageRuntime.registrations.filter { it.origin == PluginOrigin.PACKAGE } }
    val quarantined = revision.let { PluginPackageRuntime.store?.quarantinedPluginIds().orEmpty() }
    val ids = (registrations.map { it.packageId } + quarantined).distinct().sorted()
    val updatesById = updates.associateBy { it.entry.id }
    Scaffold(topBar = { TopAppBar(title = { Text("Плагіни джерел") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Зовнішні джерела встановлюються як .abplugin. Діагностика запускає реальний runtime плагіна і показує етап, на якому виникає проблема.")
            Button(onClick = ::checkCatalog, modifier = Modifier.fillMaxWidth(), enabled = !busy) { if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }; Text(if (busy) "Обробка…" else "Каталог плагінів") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Імпортувати .abplugin з файлу") }
            if (installable.isNotEmpty()) { Text("Доступні плагіни", fontWeight = FontWeight.SemiBold); installable.forEach { e -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("${e.name} v${e.version}", fontWeight = FontWeight.SemiBold); Button(onClick = { installEntry(e) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Встановити") } } } } }
            if (ids.isNotEmpty()) Text("Встановлені плагіни", fontWeight = FontWeight.SemiBold)
            ids.forEach { id ->
                val r = registrations.firstOrNull { it.packageId == id }
                PluginCard(r, id, id in quarantined, busy, updatesById[id], onUpdate = ::installUpdate,
                    onDiagnostics = { activity.startActivity(Intent(activity, PluginDiagnosticsActivity::class.java).putExtra("pluginId", id)) },
                    onToggle = { enabled -> runOperation({ if (enabled) PluginPackageRuntime.enablePackage(id) else PluginPackageRuntime.disablePackage(id) }, if (enabled) "Плагін увімкнено" else "Плагін вимкнено", "Не вдалося змінити стан") },
                    onQuarantine = { runOperation({ PluginPackageRuntime.quarantinePackage(id) }, "Переміщено в карантин", "Помилка карантину") },
                    onRestore = { runOperation({ PluginPackageRuntime.restorePackage(id) }, "Плагін відновлено", "Немає версії для відновлення") },
                    onRollback = { runOperation({ PluginPackageRuntime.rollbackPackage(id) }, "Повернуто попередню версію", "Попередньої версії немає") })
            }
            if (catalogLoaded && ids.isEmpty() && installable.isEmpty()) Text("У каталозі немає нових плагінів")
        }
    }
}

@Composable
private fun PluginCard(registration: SourcePluginRegistration?, pluginId: String, hasQuarantine: Boolean, busy: Boolean, update: PluginUpdate?, onUpdate: (PluginUpdate) -> Unit, onDiagnostics: () -> Unit, onToggle: (Boolean) -> Unit, onQuarantine: () -> Unit, onRestore: () -> Unit, onRollback: () -> Unit) {
    val d = registration?.descriptor; val enabled = registration?.state == PluginState.ENABLED
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(registration?.displayName ?: pluginId, fontWeight = FontWeight.SemiBold); Text(pluginId, style = MaterialTheme.typography.bodySmall) }; if (registration != null) Switch(enabled, onToggle, enabled = !busy && registration.state !in setOf(PluginState.QUARANTINED, PluginState.INCOMPATIBLE)) }
        Text("Стан: ${registration?.state ?: "карантин"}", style = MaterialTheme.typography.bodySmall)
        d?.let { Text("Версія ${it.version} • API ${it.apiVersion}", style = MaterialTheme.typography.bodySmall); Text("Можливості: ${it.capabilities.map { c -> c.name }.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall) }
        registration?.failureReason?.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(onClick = onDiagnostics, enabled = !busy && registration != null, modifier = Modifier.fillMaxWidth()) { Text("Діагностика") }
        if (update != null) Button(onClick = { onUpdate(update) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Оновити до v${update.entry.version}") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onRollback, enabled = !busy && registration != null, modifier = Modifier.weight(1f)) { Text("Rollback") }; OutlinedButton(onClick = onQuarantine, enabled = !busy && registration != null, modifier = Modifier.weight(1f)) { Text("Карантин") } }
        if (hasQuarantine) OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Відновити з карантину") }
    } }
}

private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Boolean { val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0L; while (true) { val read = input.read(buffer); if (read < 0) return true; total += read; if (total > maxBytes) return false; output.write(buffer, 0, read) } }
