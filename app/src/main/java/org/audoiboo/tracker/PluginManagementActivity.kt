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
import org.audoiboo.tracker.plugin.PluginArchiveLimits
import org.audoiboo.tracker.plugin.PluginCatalogEntry
import org.audoiboo.tracker.plugin.PluginInstallResult
import org.audoiboo.tracker.plugin.PluginOrigin
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.PluginState
import org.audoiboo.tracker.plugin.PluginUpdate
import org.audoiboo.tracker.plugin.PluginUpdateCheckResult
import org.audoiboo.tracker.plugin.PluginUpdateService
import org.audoiboo.tracker.plugin.SourcePluginRegistration
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
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { runCatching(block).getOrDefault(false) }
            busy = false
            toast(if (ok) success else failure)
            refresh()
        }
    }

    fun checkCatalog(showToast: Boolean = true) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { updateService.check(PluginPackageRuntime.registrations) }
            busy = false
            when (result) {
                is PluginUpdateCheckResult.Success -> {
                    updates = result.updates
                    installable = result.installable
                    catalogLoaded = true
                    if (showToast) {
                        val total = updates.size + installable.size
                        toast(
                            when {
                                total == 0 -> "Каталог перевірено — нових плагінів та оновлень немає"
                                installable.isNotEmpty() && updates.isNotEmpty() -> "Нових плагінів: ${installable.size} • оновлень: ${updates.size}"
                                installable.isNotEmpty() -> "Доступно нових плагінів: ${installable.size}"
                                else -> "Знайдено оновлень: ${updates.size}"
                            }
                        )
                    }
                }
                is PluginUpdateCheckResult.Failed -> toast("Не вдалося завантажити каталог: ${result.reason}")
            }
        }
    }

    fun installCatalogEntry(entry: PluginCatalogEntry) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                val packageFile = updateService.downloadVerified(entry, activity.cacheDir)
                    .getOrElse { return@withContext PluginInstallResult.Failed(it.message ?: "Не вдалося завантажити плагін", it) }
                try { PluginPackageRuntime.installPackage(packageFile) } finally { packageFile.delete() }
            }
            busy = false
            when (result) {
                is PluginInstallResult.Installed -> {
                    installable = installable.filterNot { it.id == entry.id }
                    toast("${result.registration.displayName} v${result.registration.descriptor?.version ?: entry.version} встановлено. Увімкни плагін перемикачем.")
                }
                is PluginInstallResult.Rejected -> toast("Плагін відхилено: ${result.reason}")
                is PluginInstallResult.Failed -> toast("Помилка встановлення: ${result.reason}")
            }
            refresh()
        }
    }

    fun installUpdate(update: PluginUpdate) {
        if (busy) return
        scope.launch {
            busy = true
            val wasEnabled = PluginPackageRuntime.registrations.firstOrNull { it.packageId == update.entry.id }?.state == PluginState.ENABLED
            val result = withContext(Dispatchers.IO) {
                val packageFile = updateService.downloadVerified(update, activity.cacheDir)
                    .getOrElse { return@withContext PluginInstallResult.Failed(it.message ?: "Не вдалося завантажити оновлення", it) }
                try {
                    val installed = PluginPackageRuntime.installPackage(packageFile)
                    if (installed is PluginInstallResult.Installed && wasEnabled) PluginPackageRuntime.enablePackage(update.entry.id)
                    installed
                } finally { packageFile.delete() }
            }
            busy = false
            when (result) {
                is PluginInstallResult.Installed -> {
                    updates = updates.filterNot { it.entry.id == update.entry.id }
                    toast("${result.registration.displayName} оновлено до v${result.registration.descriptor?.version ?: update.entry.version}")
                }
                is PluginInstallResult.Rejected -> toast("Оновлення відхилено: ${result.reason}")
                is PluginInstallResult.Failed -> toast("Помилка оновлення: ${result.reason}")
            }
            refresh()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                val temp = File(activity.cacheDir, "plugin-import-${UUID.randomUUID()}.abplugin")
                try {
                    val copied = activity.contentResolver.openInputStream(uri)?.use { input ->
                        temp.outputStream().use { output -> copyBounded(input, output, maxImportBytes) }
                    } ?: error("Не вдалося прочитати файл")
                    if (!copied) PluginInstallResult.Rejected("Файл перевищує дозволений розмір ${maxImportBytes / (1024 * 1024)} МБ")
                    else PluginPackageRuntime.installPackage(temp)
                } catch (t: Throwable) {
                    PluginInstallResult.Failed(t.message ?: "Помилка імпорту", t)
                } finally { temp.delete() }
            }
            busy = false
            when (result) {
                is PluginInstallResult.Installed -> toast("Плагін ${result.registration.displayName} v${result.registration.descriptor?.version ?: "?"} встановлено")
                is PluginInstallResult.Rejected -> toast("Плагін відхилено: ${result.reason}")
                is PluginInstallResult.Failed -> toast("Помилка встановлення: ${result.reason}")
            }
            refresh()
        }
    }

    val registrations = revision.let { PluginPackageRuntime.registrations.filter { it.origin == PluginOrigin.PACKAGE } }
    val quarantinedIds = revision.let { PluginPackageRuntime.store?.quarantinedPluginIds().orEmpty() }
    val visibleIds = (registrations.map { it.packageId } + quarantinedIds).distinct().sorted()
    val updatesById = updates.associateBy { it.entry.id }

    Scaffold(topBar = { TopAppBar(title = { Text("Плагіни джерел") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Зовнішні джерела встановлюються як .abplugin. Плагін не отримує прямого доступу до Android, файлів або мережі — HTTP виконується через sandbox застосунку.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Збірка: ${BuildProvenance.label}", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { checkCatalog() }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "Обробка…" else "Каталог плагінів")
            }
            OutlinedButton(
                onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            ) { Text("Імпортувати .abplugin з файлу") }

            if (installable.isNotEmpty()) {
                Text("Доступні плагіни", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                installable.forEach { CatalogPluginCard(it, busy, ::installCatalogEntry) }
            } else if (catalogLoaded && visibleIds.isEmpty()) {
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text("У каталозі немає нових плагінів", fontWeight = FontWeight.SemiBold)
                    Text("Можна також імпортувати сумісний .abplugin з файлу.", style = MaterialTheme.typography.bodySmall)
                } }
            }

            if (visibleIds.isNotEmpty()) Text("Встановлені плагіни", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            else if (!catalogLoaded) Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Зовнішніх плагінів ще немає", fontWeight = FontWeight.SemiBold)
                Text("Відкрий каталог або імпортуй файл .abplugin.", style = MaterialTheme.typography.bodySmall)
            } }

            visibleIds.forEach { pluginId ->
                val registration = registrations.firstOrNull { it.packageId == pluginId }
                PluginCard(
                    registration = registration,
                    pluginId = pluginId,
                    hasQuarantine = pluginId in quarantinedIds,
                    busy = busy,
                    update = updatesById[pluginId],
                    onUpdate = ::installUpdate,
                    onDiagnostics = {
                        activity.startActivity(Intent(activity, PluginDiagnosticsActivity::class.java).putExtra("pluginId", pluginId))
                    },
                    onToggle = { enabled ->
                        runOperation(
                            block = { if (enabled) PluginPackageRuntime.enablePackage(pluginId) else PluginPackageRuntime.disablePackage(pluginId) },
                            success = if (enabled) "Плагін увімкнено" else "Плагін вимкнено",
                            failure = "Не вдалося змінити стан плагіна"
                        )
                    },
                    onQuarantine = {
                        runOperation({ PluginPackageRuntime.quarantinePackage(pluginId) }, "Активну версію переміщено в карантин", "Не вдалося перемістити плагін у карантин")
                    },
                    onRestore = {
                        runOperation({ PluginPackageRuntime.restorePackage(pluginId) }, "Плагін відновлено з карантину", "Немає версії, яку можна відновити")
                    },
                    onRollback = {
                        runOperation({ PluginPackageRuntime.rollbackPackage(pluginId) }, "Повернуто попередню версію", "Попередньої версії немає")
                    }
                )
            }
        }
    }
}

@Composable
private fun CatalogPluginCard(entry: PluginCatalogEntry, busy: Boolean, onInstall: (PluginCatalogEntry) -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("${entry.id} • v${entry.version} • API ${entry.apiVersion}", style = MaterialTheme.typography.bodySmall)
        entry.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = { onInstall(entry) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Встановити") }
    } }
}

@Composable
private fun PluginCard(
    registration: SourcePluginRegistration?,
    pluginId: String,
    hasQuarantine: Boolean,
    busy: Boolean,
    update: PluginUpdate?,
    onUpdate: (PluginUpdate) -> Unit,
    onDiagnostics: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onQuarantine: () -> Unit,
    onRestore: () -> Unit,
    onRollback: () -> Unit
) {
    val descriptor = registration?.descriptor
    val manifest = registration?.manifest
    val enabled = registration?.state == PluginState.ENABLED
    val displayName = registration?.displayName ?: pluginId

    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(pluginId, style = MaterialTheme.typography.bodySmall)
            }
            if (registration != null) Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                enabled = !busy && registration.state !in setOf(PluginState.QUARANTINED, PluginState.INCOMPATIBLE)
            )
        }
        Text(
            when (registration?.state) {
                PluginState.ENABLED -> "Стан: увімкнено"
                PluginState.DISABLED -> "Стан: вимкнено"
                PluginState.QUARANTINED -> "Стан: карантин"
                PluginState.INCOMPATIBLE -> "Стан: несумісний"
                null -> "Стан: лише в карантині"
            }, style = MaterialTheme.typography.bodySmall
        )
        descriptor?.let {
            Text("Версія: ${it.version} • API: ${it.apiVersion}", style = MaterialTheme.typography.bodySmall)
            Text("Хости: ${it.hosts.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall)
            Text("Можливості: ${it.capabilities.map { c -> c.name }.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall)
        }
        manifest?.permissions?.let { permissions ->
            val flags = buildList { if (permissions.cookies) add("cookies"); if (permissions.javascript) add("javascript") }
            Text("Мережа: ${permissions.networkHosts.sorted().joinToString().ifBlank { "немає" }}${if (flags.isEmpty()) "" else " • ${flags.joinToString()}"}", style = MaterialTheme.typography.bodySmall)
            Text("Завантаження: ${permissions.effectiveDownloadHosts.sorted().joinToString().ifBlank { "немає" }}", style = MaterialTheme.typography.bodySmall)
        }
        registration?.failureReason?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (update != null) {
            Text("Доступне оновлення: v${update.entry.version}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Button(onClick = { onUpdate(update) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Оновити до v${update.entry.version}") }
        }
        OutlinedButton(onClick = onDiagnostics, enabled = !busy && registration != null, modifier = Modifier.fillMaxWidth()) { Text("Діагностика") }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRollback, enabled = !busy && registration != null, modifier = Modifier.weight(1f)) { Text("Rollback") }
            OutlinedButton(onClick = onQuarantine, enabled = !busy && registration != null, modifier = Modifier.weight(1f)) { Text("Карантин") }
        }
        if (hasQuarantine) OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Відновити з карантину") }
    } }
}

private fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Boolean {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return true
        total += read
        if (total > maxBytes) return false
        output.write(buffer, 0, read)
    }
}
