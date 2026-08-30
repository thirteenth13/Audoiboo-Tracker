package org.audoiboo.tracker

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.audoiboo.tracker.plugin.PluginInstallResult
import org.audoiboo.tracker.plugin.PluginOrigin
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.PluginState
import org.audoiboo.tracker.plugin.SourcePluginRegistration
import java.io.File
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

    fun toast(message: String) = Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    fun refresh() { revision++ }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val temp = File(activity.cacheDir, "plugin-import-${UUID.randomUUID()}.abplugin")
        runCatching {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Не вдалося прочитати файл")
            PluginPackageRuntime.installPackage(temp)
        }.onSuccess { result ->
            when (result) {
                is PluginInstallResult.Installed -> toast("Плагін ${result.registration.displayName} v${result.registration.descriptor?.version ?: "?"} встановлено")
                is PluginInstallResult.Rejected -> toast("Плагін відхилено: ${result.reason}")
                is PluginInstallResult.Failed -> toast("Помилка встановлення: ${result.reason}")
            }
            refresh()
        }.onFailure { toast("Помилка імпорту: ${it.message}") }
        temp.delete()
    }

    val registrations = revision.let {
        PluginPackageRuntime.registrations.filter { registration -> registration.origin == PluginOrigin.PACKAGE }
    }
    val quarantinedIds = revision.let { PluginPackageRuntime.store?.quarantinedPluginIds().orEmpty() }
    val visibleIds = (registrations.map { it.packageId } + quarantinedIds).distinct().sorted()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Плагіни джерел") },
                navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Зовнішні джерела встановлюються як .abplugin. Плагін не отримує прямого доступу до Android, файлів або мережі — HTTP виконується через sandbox застосунку.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { importer.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Імпортувати .abplugin")
            }

            if (visibleIds.isEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Зовнішніх плагінів ще немає", fontWeight = FontWeight.SemiBold)
                        Text("Імпортуй файл .abplugin, після перевірки він з’явиться тут вимкненим.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            visibleIds.forEach { pluginId ->
                val registration = registrations.firstOrNull { it.packageId == pluginId }
                PluginCard(
                    registration = registration,
                    pluginId = pluginId,
                    hasQuarantine = pluginId in quarantinedIds,
                    onToggle = { enabled ->
                        val ok = if (enabled) PluginPackageRuntime.enablePackage(pluginId) else PluginPackageRuntime.disablePackage(pluginId)
                        toast(if (ok) if (enabled) "Плагін увімкнено" else "Плагін вимкнено" else "Не вдалося змінити стан плагіна")
                        refresh()
                    },
                    onQuarantine = {
                        val ok = PluginPackageRuntime.quarantinePackage(pluginId)
                        toast(if (ok) "Активну версію переміщено в карантин" else "Не вдалося перемістити плагін у карантин")
                        refresh()
                    },
                    onRestore = {
                        val ok = PluginPackageRuntime.restorePackage(pluginId)
                        toast(if (ok) "Плагін відновлено з карантину" else "Немає версії, яку можна відновити")
                        refresh()
                    },
                    onRollback = {
                        val ok = PluginPackageRuntime.rollbackPackage(pluginId)
                        toast(if (ok) "Повернуто попередню версію" else "Попередньої версії немає")
                        refresh()
                    }
                )
            }
        }
    }
}

@Composable
private fun PluginCard(
    registration: SourcePluginRegistration?,
    pluginId: String,
    hasQuarantine: Boolean,
    onToggle: (Boolean) -> Unit,
    onQuarantine: () -> Unit,
    onRestore: () -> Unit,
    onRollback: () -> Unit
) {
    val descriptor = registration?.descriptor
    val manifest = registration?.manifest
    val enabled = registration?.state == PluginState.ENABLED
    val displayName = registration?.displayName ?: pluginId

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(pluginId, style = MaterialTheme.typography.bodySmall)
                }
                if (registration != null) {
                    Switch(checked = enabled, onCheckedChange = onToggle, enabled = registration.state !in setOf(PluginState.QUARANTINED, PluginState.INCOMPATIBLE))
                }
            }

            Text(
                when (registration?.state) {
                    PluginState.ENABLED -> "Стан: увімкнено"
                    PluginState.DISABLED -> "Стан: вимкнено"
                    PluginState.QUARANTINED -> "Стан: карантин"
                    PluginState.INCOMPATIBLE -> "Стан: несумісний"
                    null -> "Стан: лише в карантині"
                },
                style = MaterialTheme.typography.bodySmall
            )

            descriptor?.let {
                Text("Версія: ${it.version} • API: ${it.apiVersion}", style = MaterialTheme.typography.bodySmall)
                Text("Хости: ${it.hosts.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("Можливості: ${it.capabilities.map { capability -> capability.name }.sorted().joinToString()}", style = MaterialTheme.typography.bodySmall)
            }
            manifest?.permissions?.let { permissions ->
                val flags = buildList {
                    if (permissions.cookies) add("cookies")
                    if (permissions.javascript) add("javascript")
                }
                Text("Мережа: ${permissions.networkHosts.sorted().joinToString().ifBlank { "немає" }}${if (flags.isEmpty()) "" else " • ${flags.joinToString()}"}", style = MaterialTheme.typography.bodySmall)
            }
            registration?.failureReason?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRollback, enabled = registration != null, modifier = Modifier.weight(1f)) { Text("Rollback") }
                OutlinedButton(onClick = onQuarantine, enabled = registration != null, modifier = Modifier.weight(1f)) { Text("Карантин") }
            }
            if (hasQuarantine) {
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) { Text("Відновити з карантину") }
            }
        }
    }
}
