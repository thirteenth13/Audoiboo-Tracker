package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

object AppPrefs {
    private const val FILE = "app_settings"
    private const val BASE = "base_folder"
    private const val AUTHOR = "author_folder"
    private const val DEV = "dev_tools"
    private const val DARK = "dark_theme"
    private const val ASK = "ask_path"
    private const val WIFI = "wifi_only"
    private const val UNPACK = "unpack"
    private const val UPDATE_WIFI = "update_wifi"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun baseFolder(context: Context): String = prefs(context).getString(BASE, "Audoiboo")?.trim()?.ifBlank { "Audoiboo" } ?: "Audoiboo"
    fun useAuthorFolder(context: Context): Boolean = prefs(context).getBoolean(AUTHOR, true)
    fun devTools(context: Context): Boolean = prefs(context).getBoolean(DEV, false)
    fun darkTheme(context: Context): Boolean = prefs(context).getBoolean(DARK, false)
    fun askPath(context: Context): Boolean = prefs(context).getBoolean(ASK, false)
    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(WIFI, false)
    fun unpack(context: Context): Boolean = prefs(context).getBoolean(UNPACK, false)
    fun updateWifiOnly(context: Context): Boolean = prefs(context).getBoolean(UPDATE_WIFI, true)

    fun save(
        context: Context,
        baseFolder: String,
        authorFolder: Boolean,
        devTools: Boolean,
        darkTheme: Boolean,
        askPath: Boolean,
        wifiOnly: Boolean,
        unpack: Boolean,
        updateWifiOnly: Boolean
    ) {
        prefs(context).edit()
            .putString(BASE, baseFolder.trim().trim('/'))
            .putBoolean(AUTHOR, authorFolder)
            .putBoolean(DEV, devTools)
            .putBoolean(DARK, darkTheme)
            .putBoolean(ASK, askPath)
            .putBoolean(WIFI, wifiOnly)
            .putBoolean(UNPACK, unpack)
            .putBoolean(UPDATE_WIFI, updateWifiOnly)
            .apply()
    }
}

@Composable
fun AudoibooTheme(context: Context, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (AppPrefs.darkTheme(context)) darkColorScheme() else lightColorScheme(),
        content = content
    )
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
    var base by remember { mutableStateOf(AppPrefs.baseFolder(activity)) }
    var author by remember { mutableStateOf(AppPrefs.useAuthorFolder(activity)) }
    var dev by remember { mutableStateOf(AppPrefs.devTools(activity)) }
    var dark by remember { mutableStateOf(AppPrefs.darkTheme(activity)) }
    var ask by remember { mutableStateOf(AppPrefs.askPath(activity)) }
    var wifi by remember { mutableStateOf(AppPrefs.wifiOnly(activity)) }
    var unpack by remember { mutableStateOf(AppPrefs.unpack(activity)) }
    var updateWifi by remember { mutableStateOf(AppPrefs.updateWifiOnly(activity)) }

    fun save() {
        AppPrefs.save(activity, base, author, dev, dark, ask, wifi, unpack, updateWifi)
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Налаштування") },
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
                SectionTitle("Зовнішній вигляд")
                SettingCard(
                    title = "Тема інтерфейсу",
                    subtitle = if (dark) "Темна (Material You)" else "Світла (Material 3)"
                ) {
                    Switch(checked = dark, onCheckedChange = { dark = it; save() })
                }

                SectionTitle("Завантаження")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = base,
                            onValueChange = { base = it },
                            label = { Text("Базова папка") },
                            supportingText = { Text("/storage/emulated/0/Download/${base.ifBlank { "Audoiboo" }}") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("Структура папок", style = MaterialTheme.typography.titleSmall)
                        Text(if (author) "Автор → Серія → Книга" else "Серія → Книга")
                        Text(
                            "${base.ifBlank { "Audoiboo" }}/${if (author) "Автор/" else ""}Серія/Книга.zip",
                            style = MaterialTheme.typography.bodySmall
                        )
                        SettingRow("Папка автора", "Автор першим рівнем") {
                            Switch(checked = author, onCheckedChange = { author = it; save() })
                        }
                        SettingRow("Запитувати шлях завантаження", "Перед кожним завантаженням") {
                            Switch(checked = ask, onCheckedChange = { ask = it; save() })
                        }
                        SettingRow("Завантажувати тільки по Wi‑Fi", "Економія мобільного трафіку") {
                            Switch(checked = wifi, onCheckedChange = { wifi = it; save() })
                        }
                        SettingRow("Розпаковувати архіви", "Після завантаження (dev)") {
                            Switch(checked = unpack, onCheckedChange = { unpack = it; save() })
                        }
                        Button(onClick = { save() }) { Text("Зберегти") }
                    }
                }

                SectionTitle("Інше")
                SettingCard("Перевіряти оновлення", if (updateWifi) "Лише по Wi‑Fi" else "Будь-яка мережа") {
                    Switch(checked = updateWifi, onCheckedChange = { updateWifi = it; save() })
                }
                Card(Modifier.fillMaxWidth().clickable { }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Про додаток")
                        Text("Audoiboo Tracker 0.3.1-dev", style = MaterialTheme.typography.bodySmall)
                    }
                }

                SectionTitle("Розробка")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        SettingRow("Інструменти налагодження", "DOM-діагностика") {
                            Switch(checked = dev, onCheckedChange = { dev = it; save() })
                        }
                        if (dev) {
                            OutlinedButton(onClick = {
                                activity.startActivity(Intent(activity, DiagnosticActivity::class.java))
                            }) { Text("Відкрити DOM-діагностику") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SettingCard(title: String, subtitle: String, end: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) { SettingRow(title, subtitle, end) }
}

@Composable
private fun SettingRow(title: String, subtitle: String, end: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        end()
    }
}
