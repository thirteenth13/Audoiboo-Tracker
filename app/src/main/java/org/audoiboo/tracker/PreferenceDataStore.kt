package org.audoiboo.tracker

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.audoibooDataStore by preferencesDataStore(name = "audoiboo_settings")

private const val CURRENT_SETTINGS_SCHEMA = 3

data class ModernSettings(
    val baseFolder: String = "Audoiboo",
    val authorFolder: Boolean = true,
    val devTools: Boolean = false,
    val darkTheme: Boolean = false,
    val askPath: Boolean = false,
    val wifiOnly: Boolean = false,
    val unpack: Boolean = true,
    val updateWifiOnly: Boolean = true,
    val showPageButton: Boolean = true,
    val showFindArchiveButton: Boolean = true,
    val autoFindArchives: Boolean = false,
    val voiceBoost: Boolean = false,
    val gainMb: Int = 600,
    val seriesAutomationEnabled: Boolean = false,
    val seriesAutoDownload: Boolean = false,
    val seriesWifiOnly: Boolean = true,
    val schemaVersion: Int = CURRENT_SETTINGS_SCHEMA
)

object PreferenceDataStore {
    private const val LEGACY_FILE = "app_settings"
    private const val LEGACY_AUDIO_FILE = "audio_enhancement"
    private const val LEGACY_SERIES_FILE = "series_automation"
    private val BASE_FOLDER = stringPreferencesKey("base_folder")
    private val AUTHOR_FOLDER = booleanPreferencesKey("author_folder")
    private val DEV_TOOLS = booleanPreferencesKey("dev_tools")
    private val DARK_THEME = booleanPreferencesKey("dark_theme")
    private val ASK_PATH = booleanPreferencesKey("ask_path")
    private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    private val UNPACK = booleanPreferencesKey("unpack")
    private val UPDATE_WIFI = booleanPreferencesKey("update_wifi")
    private val SHOW_PAGE = booleanPreferencesKey("show_page_button")
    private val SHOW_FIND = booleanPreferencesKey("show_find_archive_button")
    private val AUTO_ARCHIVES = booleanPreferencesKey("auto_find_archives")
    private val VOICE_BOOST = booleanPreferencesKey("voice_boost")
    private val GAIN_MB = intPreferencesKey("gain_mb")
    private val SERIES_AUTOMATION_ENABLED = booleanPreferencesKey("series_automation_enabled")
    private val SERIES_AUTO_DOWNLOAD = booleanPreferencesKey("series_auto_download")
    private val SERIES_WIFI_ONLY = booleanPreferencesKey("series_wifi_only")
    private val VERSION = intPreferencesKey("schema_version")
    private val LEGACY_IMPORTED = booleanPreferencesKey("legacy_imported")

    fun observe(context: Context): Flow<ModernSettings> = context.applicationContext.audoibooDataStore.data.map(::fromPreferences)

    suspend fun current(context: Context): ModernSettings = fromPreferences(context.applicationContext.audoibooDataStore.data.first())

    suspend fun importLegacyIfNeeded(context: Context) {
        val app = context.applicationContext
        app.audoibooDataStore.edit { p ->
            val imported = p[LEGACY_IMPORTED] == true
            val version = p[VERSION] ?: 0
            if (!imported) {
                copyLegacy(app, p)
                p[LEGACY_IMPORTED] = true
                p[VERSION] = CURRENT_SETTINGS_SCHEMA
            } else if (version < CURRENT_SETTINGS_SCHEMA) {
                // v2 DataStore values are already authoritative. Import only the settings that
                // still lived in their own legacy SharedPreferences files so modern changes are
                // never overwritten by stale app_settings values during the v2 -> v3 upgrade.
                copySpecializedLegacy(app, p)
                p[VERSION] = CURRENT_SETTINGS_SCHEMA
            }
        }
    }

    suspend fun reconcile(context: Context) {
        val app = context.applicationContext
        val p = app.audoibooDataStore.data.first()
        if (p[LEGACY_IMPORTED] != true || (p[VERSION] ?: 0) < CURRENT_SETTINGS_SCHEMA) importLegacyIfNeeded(app)
    }

    internal fun legacySnapshot(context: Context): ModernSettings {
        val app = context.applicationContext
        val p = app.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val audio = app.getSharedPreferences(LEGACY_AUDIO_FILE, Context.MODE_PRIVATE)
        val series = app.getSharedPreferences(LEGACY_SERIES_FILE, Context.MODE_PRIVATE)
        return ModernSettings(
            baseFolder = p.getString("base_folder", "Audoiboo")?.trim()?.ifBlank { "Audoiboo" } ?: "Audoiboo",
            authorFolder = p.getBoolean("author_folder", true),
            devTools = p.getBoolean("dev_tools", false),
            darkTheme = p.getBoolean("dark_theme", false),
            askPath = p.getBoolean("ask_path", false),
            wifiOnly = p.getBoolean("wifi_only", false),
            unpack = p.getBoolean("unpack", true),
            updateWifiOnly = p.getBoolean("update_wifi", true),
            showPageButton = p.getBoolean("show_page_button", true),
            showFindArchiveButton = p.getBoolean("show_find_archive_button", true),
            autoFindArchives = p.getBoolean("auto_find_archives", false),
            voiceBoost = audio.getBoolean("voice_boost", false),
            gainMb = audio.getInt("gain_mb", 600).coerceIn(0, 1200),
            seriesAutomationEnabled = series.getBoolean("enabled", false),
            seriesAutoDownload = series.getBoolean("auto_download", false),
            seriesWifiOnly = series.getBoolean("wifi_only", true),
            schemaVersion = CURRENT_SETTINGS_SCHEMA
        )
    }

    private fun copyLegacy(context: Context, p: MutablePreferences) {
        put(p, legacySnapshot(context))
    }

    private fun copySpecializedLegacy(context: Context, p: MutablePreferences) {
        val app = context.applicationContext
        val audio = app.getSharedPreferences(LEGACY_AUDIO_FILE, Context.MODE_PRIVATE)
        val series = app.getSharedPreferences(LEGACY_SERIES_FILE, Context.MODE_PRIVATE)
        if (p[VOICE_BOOST] == null) p[VOICE_BOOST] = audio.getBoolean("voice_boost", false)
        if (p[GAIN_MB] == null) p[GAIN_MB] = audio.getInt("gain_mb", 600).coerceIn(0, 1200)
        if (p[SERIES_AUTOMATION_ENABLED] == null) p[SERIES_AUTOMATION_ENABLED] = series.getBoolean("enabled", false)
        if (p[SERIES_AUTO_DOWNLOAD] == null) p[SERIES_AUTO_DOWNLOAD] = series.getBoolean("auto_download", false)
        if (p[SERIES_WIFI_ONLY] == null) p[SERIES_WIFI_ONLY] = series.getBoolean("wifi_only", true)
    }

    private fun fromPreferences(p: Preferences): ModernSettings = ModernSettings(
        baseFolder = p[BASE_FOLDER] ?: "Audoiboo",
        authorFolder = p[AUTHOR_FOLDER] ?: true,
        devTools = p[DEV_TOOLS] ?: false,
        darkTheme = p[DARK_THEME] ?: false,
        askPath = p[ASK_PATH] ?: false,
        wifiOnly = p[WIFI_ONLY] ?: false,
        unpack = p[UNPACK] ?: true,
        updateWifiOnly = p[UPDATE_WIFI] ?: true,
        showPageButton = p[SHOW_PAGE] ?: true,
        showFindArchiveButton = p[SHOW_FIND] ?: true,
        autoFindArchives = p[AUTO_ARCHIVES] ?: false,
        voiceBoost = p[VOICE_BOOST] ?: false,
        gainMb = (p[GAIN_MB] ?: 600).coerceIn(0, 1200),
        seriesAutomationEnabled = p[SERIES_AUTOMATION_ENABLED] ?: false,
        seriesAutoDownload = p[SERIES_AUTO_DOWNLOAD] ?: false,
        seriesWifiOnly = p[SERIES_WIFI_ONLY] ?: true,
        schemaVersion = p[VERSION] ?: CURRENT_SETTINGS_SCHEMA
    )

    suspend fun save(context: Context, value: ModernSettings) {
        val clean = value.copy(
            baseFolder = value.baseFolder.trim().trim('/').ifBlank { "Audoiboo" },
            gainMb = value.gainMb.coerceIn(0, 1200),
            schemaVersion = CURRENT_SETTINGS_SCHEMA
        )
        context.applicationContext.audoibooDataStore.edit { p ->
            put(p, clean)
            p[LEGACY_IMPORTED] = true
        }
    }

    suspend fun exportJson(context: Context): JSONObject = current(context).toJson()

    suspend fun restoreJson(context: Context, json: JSONObject?) {
        if (json == null) return
        val value = ModernSettings(
            baseFolder = json.optString("baseFolder", "Audoiboo").trim().trim('/').ifBlank { "Audoiboo" },
            authorFolder = json.optBoolean("authorFolder", true),
            devTools = json.optBoolean("devTools", false),
            darkTheme = json.optBoolean("darkTheme", false),
            askPath = json.optBoolean("askPath", false),
            wifiOnly = json.optBoolean("wifiOnly", false),
            unpack = json.optBoolean("unpack", true),
            updateWifiOnly = json.optBoolean("updateWifiOnly", true),
            showPageButton = json.optBoolean("showPageButton", true),
            showFindArchiveButton = json.optBoolean("showFindArchiveButton", true),
            autoFindArchives = json.optBoolean("autoFindArchives", false),
            voiceBoost = json.optBoolean("voiceBoost", false),
            gainMb = json.optInt("gainMb", 600).coerceIn(0, 1200),
            seriesAutomationEnabled = json.optBoolean("seriesAutomationEnabled", false),
            seriesAutoDownload = json.optBoolean("seriesAutoDownload", false),
            seriesWifiOnly = json.optBoolean("seriesWifiOnly", true),
            schemaVersion = CURRENT_SETTINGS_SCHEMA
        )
        save(context, value)
        AppSettingsStore.refresh(context.applicationContext)
    }

    private fun put(p: MutablePreferences, value: ModernSettings) {
        p[BASE_FOLDER] = value.baseFolder.trim().trim('/').ifBlank { "Audoiboo" }
        p[AUTHOR_FOLDER] = value.authorFolder
        p[DEV_TOOLS] = value.devTools
        p[DARK_THEME] = value.darkTheme
        p[ASK_PATH] = value.askPath
        p[WIFI_ONLY] = value.wifiOnly
        p[UNPACK] = value.unpack
        p[UPDATE_WIFI] = value.updateWifiOnly
        p[SHOW_PAGE] = value.showPageButton
        p[SHOW_FIND] = value.showFindArchiveButton
        p[AUTO_ARCHIVES] = value.autoFindArchives
        p[VOICE_BOOST] = value.voiceBoost
        p[GAIN_MB] = value.gainMb.coerceIn(0, 1200)
        p[SERIES_AUTOMATION_ENABLED] = value.seriesAutomationEnabled
        p[SERIES_AUTO_DOWNLOAD] = value.seriesAutoDownload
        p[SERIES_WIFI_ONLY] = value.seriesWifiOnly
        p[VERSION] = CURRENT_SETTINGS_SCHEMA
    }

    private fun ModernSettings.toJson(): JSONObject = JSONObject()
        .put("baseFolder", baseFolder)
        .put("authorFolder", authorFolder)
        .put("devTools", devTools)
        .put("darkTheme", darkTheme)
        .put("askPath", askPath)
        .put("wifiOnly", wifiOnly)
        .put("unpack", unpack)
        .put("updateWifiOnly", updateWifiOnly)
        .put("showPageButton", showPageButton)
        .put("showFindArchiveButton", showFindArchiveButton)
        .put("autoFindArchives", autoFindArchives)
        .put("voiceBoost", voiceBoost)
        .put("gainMb", gainMb)
        .put("seriesAutomationEnabled", seriesAutomationEnabled)
        .put("seriesAutoDownload", seriesAutoDownload)
        .put("seriesWifiOnly", seriesWifiOnly)
        .put("schemaVersion", CURRENT_SETTINGS_SCHEMA)

    suspend fun setWifiOnly(context: Context, value: Boolean) = save(context, current(context).copy(wifiOnly = value))
    suspend fun setAutoFindArchives(context: Context, value: Boolean) = save(context, current(context).copy(autoFindArchives = value))
    suspend fun setDarkTheme(context: Context, value: Boolean) = save(context, current(context).copy(darkTheme = value))
}
