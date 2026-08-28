package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.os.Build
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
    private const val SHOW_PAGE = "show_page_button"
    private const val SHOW_FIND = "show_find_archive_button"
    private const val AUTO_ARCHIVES = "auto_find_archives"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun baseFolder(context: Context): String = prefs(context).getString(BASE, "Audoiboo")?.trim()?.ifBlank { "Audoiboo" } ?: "Audoiboo"
    fun useAuthorFolder(context: Context): Boolean = prefs(context).getBoolean(AUTHOR, true)
    fun devTools(context: Context): Boolean = prefs(context).getBoolean(DEV, false)
    fun darkTheme(context: Context): Boolean = prefs(context).getBoolean(DARK, false)
    fun askPath(context: Context): Boolean = prefs(context).getBoolean(ASK, false)
    fun wifiOnly(context: Context): Boolean = prefs(context).getBoolean(WIFI, false)
    fun unpack(context: Context): Boolean = prefs(context).getBoolean(UNPACK, true)
    fun updateWifiOnly(context: Context): Boolean = prefs(context).getBoolean(UPDATE_WIFI, true)
    fun showPageButton(context: Context): Boolean = prefs(context).getBoolean(SHOW_PAGE, true)
    fun showFindArchiveButton(context: Context): Boolean = prefs(context).getBoolean(SHOW_FIND, true)
    fun autoFindArchives(context: Context): Boolean = prefs(context).getBoolean(AUTO_ARCHIVES, false)

    fun save(context: Context, baseFolder: String, authorFolder: Boolean, devTools: Boolean, darkTheme: Boolean, askPath: Boolean, wifiOnly: Boolean, unpack: Boolean, updateWifiOnly: Boolean, showPage: Boolean, showFind: Boolean, autoArchives: Boolean) {
        prefs(context).edit().putString(BASE, baseFolder.trim().trim('/')).putBoolean(AUTHOR, authorFolder).putBoolean(DEV, devTools).putBoolean(DARK, darkTheme).putBoolean(ASK, askPath).putBoolean(WIFI, wifiOnly).putBoolean(UNPACK, unpack).putBoolean(UPDATE_WIFI, updateWifiOnly).putBoolean(SHOW_PAGE, showPage).putBoolean(SHOW_FIND, showFind).putBoolean(AUTO_ARCHIVES, autoArchives).apply()
    }
}

@Composable
fun AudoibooTheme(context: Context, content: @Composable () -> Unit) {
    val dark = AppPrefs.darkTheme(context)
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SettingsScreen(this) } }
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
    var showPage by remember { mutableStateOf(AppPrefs.showPageButton(activity)) }
    var showFind by remember { mutableStateOf(AppPrefs.showFindArchiveButton(activity)) }
    var autoArchives by remember { mutableStateOf(AppPrefs.autoFindArchives(activity)) }
    var storageName by remember { mutableStateOf(StorageAccess.displayName(activity)) }

    val automatic = remember { BackupStore.automaticSettings(activity) }
    var autoSettings by remember { mutableStateOf(automatic.first) }
    var autoBookmarks by remember { mutableStateOf(automatic.second) }
    var autoStatistics by remember { mutableStateOf(automatic.third) }
    var autoPath by remember { mutableStateOf(BackupStore.automaticBackupPath(activity)) }

    var webDavUrl by remember { mutableStateOf(WebDavSync.url(activity)) }
    var webDavUser by remember { mutableStateOf(WebDavSync.user(activity)) }
    var webDavPass by remember { mutableStateOf(WebDavSync.password(activity)) }
    var webDavEnabled by remember { mutableStateOf(WebDavSync.enabled(activity)) }

    fun save() { AppPrefs.save(activity, base, author, dev, dark, ask, wifi, unpack, updateWifi, showPage, showFind, autoArchives) }
    fun saveAutomatic() { BackupStore.setAutomaticSettings(activity, autoSettings, autoBookmarks, autoStatistics); BackupStore.setAutomaticBackupPath(activity, autoPath); BackupStore.maybeCreateDailyBackup(activity) }
    fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_LONG).show()

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            StorageAccess.setTree(activity, uri); storageName = StorageAccess.displayName(activity); toast("Папку бібліотеки збережено")
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runCatching { activity.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(BackupStore.exportJson(activity)) } }
            .onSuccess { toast("Резервну копію створено") }.onFailure { toast("Помилка резервної копії: ${it.message}") }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching { val raw = activity.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Порожній файл"); BackupStore.importJson(activity, raw) }
            .onSuccess { toast("Дані відновлено. Перезапусти додаток") }.onFailure { toast("Помилка відновлення: ${it.message}") }
    }

    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { if (dark) dynamicDarkColorScheme(activity) else dynamicLightColorScheme(activity) } else if (dark) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Scaffold(topBar = { TopAppBar(title = { Text("Налаштування") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("←") } }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Зовнішній вигляд")
                SettingCard("Тема інтерфейсу", if (dark) "Темна • Material You" else "Світла • Material You") { Switch(checked = dark, onCheckedChange = { dark = it; save() }) }

                SectionTitle("Плеєр")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Налаштування вбудованого плеєра", style = MaterialTheme.typography.titleMedium); Text("Швидкість тепер запам’ятовується окремо для кожної книги.", style = MaterialTheme.typography.bodySmall); OutlinedButton(onClick = { activity.startActivity(Intent(activity, PlayerSettingsActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Налаштування плеєра") } } }

                SectionTitle("Картка книги")
                SettingCard("Кнопка «Сторінка»", "Відкривати сторінку книги в браузері") { Switch(checked = showPage, onCheckedChange = { showPage = it; save() }) }
                SettingCard("Кнопка «Знайти архів»", "Ручний пошук посилання на архів") { Switch(checked = showFind, onCheckedChange = { showFind = it; save() }) }
                SettingCard("Автоматично шукати архіви", "Після оновлення серії перевіряти книги без архіву") { Switch(checked = autoArchives, onCheckedChange = { autoArchives = it; save() }) }

                SectionTitle("Завантаження")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("Базова папка") }, supportingText = { Text(if (storageName == null) "/storage/emulated/0/Download/${base.ifBlank { "Audoiboo" }}" else "У вибраній SAF-папці: ${base.ifBlank { "Audoiboo" }}") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Структура: ${if (author) "Автор → Серія → Книга" else "Серія → Книга"}")
                    SettingRow("Папка автора", "Автор першим рівнем") { Switch(checked = author, onCheckedChange = { author = it; save() }) }
                    SettingRow("Завантажувати тільки по Wi‑Fi", "WorkManager чекатиме Wi‑Fi, downloader залишиться foreground") { Switch(checked = wifi, onCheckedChange = { wifi = it; save() }) }
                    SettingRow("Розпаковувати ZIP", "Перед розпакуванням перевіряється CRC") { Switch(checked = unpack, onCheckedChange = { unpack = it; save() }) }
                    OutlinedButton(onClick = { folderLauncher.launch(StorageAccess.treeUri(activity)) }, modifier = Modifier.fillMaxWidth()) { Text(if (storageName == null) "Вибрати папку через SAF" else "Папка: $storageName") }
                    if (storageName != null) TextButton(onClick = { StorageAccess.setTree(activity, null); storageName = null }) { Text("Повернутися до Downloads/Audoiboo") }
                    Button(onClick = { save() }) { Text("Зберегти") }
                } }

                SectionTitle("WebDAV синхронізація")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FOSS-сумісна синхронізація прогресу, закладок і налаштувань через Nextcloud/Synology/WebDAV.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(webDavUrl, { webDavUrl = it }, label = { Text("WebDAV URL папки") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(webDavUser, { webDavUser = it }, label = { Text("Користувач") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(webDavPass, { webDavPass = it }, label = { Text("Пароль / app password") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    SettingRow("Автосинхронізація", "Раз на 12 годин при доступній мережі") { Switch(webDavEnabled, { webDavEnabled = it; WebDavSync.save(activity, webDavUrl, webDavUser, webDavPass, it) }) }
                    Button(onClick = { WebDavSync.save(activity, webDavUrl, webDavUser, webDavPass, webDavEnabled); Thread { runCatching { WebDavSync.upload(activity) }.onSuccess { activity.runOnUiThread { toast("WebDAV: дані завантажено") } }.onFailure { activity.runOnUiThread { toast("WebDAV: ${it.message}") } } }.start() }, modifier = Modifier.fillMaxWidth()) { Text("Синхронізувати на сервер") }
                    OutlinedButton(onClick = { WebDavSync.save(activity, webDavUrl, webDavUser, webDavPass, webDavEnabled); Thread { runCatching { WebDavSync.download(activity) }.onSuccess { activity.runOnUiThread { toast("WebDAV: дані відновлено") } }.onFailure { activity.runOnUiThread { toast("WebDAV: ${it.message}") } } }.start() }, modifier = Modifier.fillMaxWidth()) { Text("Відновити з WebDAV") }
                } }

                SectionTitle("Автоматичне резервне копіювання")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(value = autoPath, onValueChange = { autoPath = it }, label = { Text("Папка автобекапів") }, supportingText = { Text("За замовчуванням: ${BackupStore.DEFAULT_AUTO_PATH}") }, modifier = Modifier.fillMaxWidth(), singleLine = true); Button(onClick = { BackupStore.setAutomaticBackupPath(activity, autoPath); toast("Шлях автобекапів збережено") }) { Text("Зберегти шлях") } } }
                SettingCard("Автоматичне резервне копіювання налаштувань", "Щоденна локальна резервна копія") { Switch(checked = autoSettings, onCheckedChange = { autoSettings = it; saveAutomatic() }) }
                SettingCard("Автоматичне резервне копіювання закладок", "Щоденна резервна копія закладок") { Switch(checked = autoBookmarks, onCheckedChange = { autoBookmarks = it; saveAutomatic() }) }
                SettingCard("Автоматичне резервне копіювання статистики", "Щоденна резервна копія позицій і прогресу") { Switch(checked = autoStatistics, onCheckedChange = { autoStatistics = it; saveAutomatic() }) }
                Text("Зберігаються до 7 автоматичних копій у вибраній папці.", style = MaterialTheme.typography.bodySmall)

                SectionTitle("Резервна копія")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Повна ручна копія містить серії, книги, статуси, архіви, завантаження, налаштування плеєра, закладки та позиції відтворення."); Button(onClick = { backupLauncher.launch("Audoiboo-Tracker-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("Створити резервну копію") }; OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/json", "text/plain")) }, modifier = Modifier.fillMaxWidth()) { Text("Відновити з копії") } } }

                SectionTitle("Інше")
                SettingCard("Перевіряти оновлення", if (updateWifi) "Лише по Wi‑Fi" else "Будь-яка мережа") { Switch(checked = updateWifi, onCheckedChange = { updateWifi = it; save() }) }
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Про додаток"); Text("Audoiboo Tracker 1.1.3-dev", style = MaterialTheme.typography.bodySmall) } }

                SectionTitle("Розробка")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { SettingRow("Інструменти налагодження", "DOM-діагностика") { Switch(checked = dev, onCheckedChange = { dev = it; save() }) }; if (dev) OutlinedButton(onClick = { activity.startActivity(Intent(activity, DiagnosticActivity::class.java)) }) { Text("Відкрити DOM-діагностику") } } }
            }
        }
    }
}

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
@Composable private fun SettingCard(title: String, subtitle: String, end: @Composable () -> Unit) { Card(Modifier.fillMaxWidth()) { SettingRow(title, subtitle, end) } }
@Composable private fun SettingRow(title: String, subtitle: String, end: @Composable () -> Unit) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) { Column(Modifier.weight(1f)) { Text(title); Text(subtitle, style = MaterialTheme.typography.bodySmall) }; end() } }
