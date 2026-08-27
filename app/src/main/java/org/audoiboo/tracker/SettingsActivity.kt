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
    private const val KEY_DARK_THEME = "dark_theme"

    fun baseFolder(context: Context): String = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        .getString(KEY_BASE_FOLDER, "Audoiboo")?.trim()?.ifBlank { "Audoiboo" } ?: "Audoiboo"
    fun useAuthorFolder(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTHOR_FOLDER, true)
    fun devTools(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DEV_TOOLS, false)
    fun darkTheme(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_DARK_THEME, false)

    fun save(context: Context, baseFolder: String, authorFolder: Boolean, devTools: Boolean, darkTheme: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_BASE_FOLDER, baseFolder.trim().trim('/'))
            .putBoolean(KEY_AUTHOR_FOLDER, authorFolder)
            .putBoolean(KEY_DEV_TOOLS, devTools)
            .putBoolean(KEY_DARK_THEME, darkTheme)
            .apply()
    }
}

@Composable
fun AudoibooTheme(context: Context, content: @Composable () -> Unit) {
    val dark = AppPrefs.darkTheme(context)
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsScreen(this) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(activity: ComponentActivity) {
    var baseFolder by remember { mutableStateOf(AppPrefs.baseFolder(activity)) }
    var authorFolder by remember { mutableStateOf(AppPrefs.useAuthorFolder(activity)) }
    var devTools by remember { mutableStateOf(AppPrefs.devTools(activity)) }
    var darkTheme by remember { mutableStateOf(AppPrefs.darkTheme(activity)) }

    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Налаштування") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } })
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Зовнішній вигляд", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("Тема інтерфейсу"); Text(if (darkTheme) "Темна (Material You)" else "Світла (Material 3)", style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = darkTheme, onCheckedChange = { darkTheme = it; AppPrefs.save(activity, baseFolder, authorFolder, devTools, it) })
                    }
                }

                Text("Завантаження", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = baseFolder, onValueChange = { baseFolder = it }, label = { Text("Базова папка в Downloads") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Структура папок", style = MaterialTheme.typography.titleSmall)
                    Text(if (authorFolder) "Автор → Серія → Книга" else "Серія → Книга", style = MaterialTheme.typography.bodyMedium)
                    Text("Downloads/${baseFolder.ifBlank { "Audoiboo" }}/${if (authorFolder) "Автор/" else ""}Серія/Книга.zip", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) { Text("Папка автора"); Text("Додати автора першим рівнем", style = MaterialTheme.typography.bodySmall) }
                        Switch(checked = authorFolder, onCheckedChange = { authorFolder = it })
                    }
                    Button(onClick = { AppPrefs.save(activity, baseFolder, authorFolder, devTools, darkTheme) }) { Text("Зберегти") }
                } }

                Text("Розробка", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Інструменти налагодження", modifier = Modifier.weight(1f))
                        Switch(checked = devTools, onCheckedChange = { devTools = it; AppPrefs.save(activity, baseFolder, authorFolder, it, darkTheme) })
                    }
                    if (devTools) OutlinedButton(onClick = { activity.startActivity(Intent(activity, DiagnosticActivity::class.java)) }) { Text("Відкрити DOM-діагностику") }
                } }
                Spacer(Modifier.weight(1f))
                Text("Audoiboo Tracker 0.3.0-dev", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
