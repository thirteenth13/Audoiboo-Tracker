package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object AppPrefs {
    private const val FILE = "app_settings"
    private const val KEY_BASE_FOLDER = "base_folder"
    private const val KEY_AUTHOR_FOLDER = "author_folder"
    private const val KEY_DEV_TOOLS = "dev_tools"

    fun baseFolder(context: Context): String = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY_BASE_FOLDER, "Audoiboo")?.trim()?.ifBlank { "Audoiboo" } ?: "Audoiboo"

    fun useAuthorFolder(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(KEY_AUTHOR_FOLDER, true)

    fun devTools(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getBoolean(KEY_DEV_TOOLS, false)

    fun save(context: Context, baseFolder: String, authorFolder: Boolean, devTools: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_FOLDER, baseFolder.trim().trim('/'))
            .putBoolean(KEY_AUTHOR_FOLDER, authorFolder)
            .putBoolean(KEY_DEV_TOOLS, devTools)
            .apply()
    }
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SettingsScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: ComponentActivity) {
    var baseFolder by remember { mutableStateOf(AppPrefs.baseFolder(activity)) }
    var authorFolder by remember { mutableStateOf(AppPrefs.useAuthorFolder(activity)) }
    var devTools by remember { mutableStateOf(AppPrefs.devTools(activity)) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Налаштування") },
            navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }
        )
    }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Завантаження", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = baseFolder,
                onValueChange = { baseFolder = it },
                label = { Text("Папка всередині Downloads") },
                supportingText = { Text("Фактичний шлях: Downloads/${baseFolder.ifBlank { "Audoiboo" }}/Серія/…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("Підпапка автора")
                    Text("Downloads/База/Серія/Автор/книга.zip", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = authorFolder, onCheckedChange = { authorFolder = it })
            }

            Button(onClick = {
                AppPrefs.save(activity, baseFolder, authorFolder, devTools)
            }) { Text("Зберегти") }

            HorizontalDivider()
            Text("Розробка", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Показати інструменти налагодження")
                Switch(checked = devTools, onCheckedChange = {
                    devTools = it
                    AppPrefs.save(activity, baseFolder, authorFolder, it)
                })
            }
            if (devTools) {
                OutlinedButton(onClick = {
                    activity.startActivity(Intent(activity, DiagnosticActivity::class.java))
                }) { Text("Відкрити DOM-діагностику") }
            }

            Spacer(Modifier.weight(1f))
            Text("Audoiboo Tracker 0.3.0-dev", style = MaterialTheme.typography.bodySmall)
        }
    }
}
