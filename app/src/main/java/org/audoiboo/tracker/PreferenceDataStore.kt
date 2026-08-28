package org.audoiboo.tracker

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.audoibooDataStore by preferencesDataStore(name = "audoiboo_settings")

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
    val schemaVersion: Int = 2
)

object PreferenceDataStore {
    private const val LEGACY_FILE = "app_settings"
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
    private val VERSION = intPreferencesKey("schema_version")
    private val LEGACY_IMPORTED = booleanPreferencesKey("legacy_imported")

    fun observe(context: Context): Flow<ModernSettings> = context.applicationContext.audoibooDataStore.data.map { p ->
        ModernSettings(
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
            schemaVersion = p[VERSION] ?: 2
        )
    }

    suspend fun importLegacyIfNeeded(context: Context) {
        context.applicationContext.audoibooDataStore.edit { p ->
            if (p[LEGACY_IMPORTED] == true && (p[VERSION] ?: 0) >= 2) return@edit
            copyLegacy(context.applicationContext, p)
            p[LEGACY_IMPORTED] = true
        }
    }

    suspend fun syncFromLegacy(context: Context) {
        context.applicationContext.audoibooDataStore.edit { p ->
            copyLegacy(context.applicationContext, p)
            p[LEGACY_IMPORTED] = true
        }
    }

    /** Prefer legacy values while compatibility code exists; rebuild them from DataStore if removed. */
    suspend fun reconcile(context: Context) {
        val app = context.applicationContext
        val legacy = app.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            syncFromLegacy(app)
            return
        }
        val p = app.audoibooDataStore.data.first()
        if (p[LEGACY_IMPORTED] != true || (p[VERSION] ?: 0) < 2) {
            importLegacyIfNeeded(app)
            return
        }
        legacy.edit()
            .putString("base_folder", p[BASE_FOLDER] ?: "Audoiboo")
            .putBoolean("author_folder", p[AUTHOR_FOLDER] ?: true)
            .putBoolean("dev_tools", p[DEV_TOOLS] ?: false)
            .putBoolean("dark_theme", p[DARK_THEME] ?: false)
            .putBoolean("ask_path", p[ASK_PATH] ?: false)
            .putBoolean("wifi_only", p[WIFI_ONLY] ?: false)
            .putBoolean("unpack", p[UNPACK] ?: true)
            .putBoolean("update_wifi", p[UPDATE_WIFI] ?: true)
            .putBoolean("show_page_button", p[SHOW_PAGE] ?: true)
            .putBoolean("show_find_archive_button", p[SHOW_FIND] ?: true)
            .putBoolean("auto_find_archives", p[AUTO_ARCHIVES] ?: false)
            .apply()
    }

    private fun copyLegacy(context: Context, p: MutablePreferences) {
        p[BASE_FOLDER] = AppPrefs.baseFolder(context)
        p[AUTHOR_FOLDER] = AppPrefs.useAuthorFolder(context)
        p[DEV_TOOLS] = AppPrefs.devTools(context)
        p[DARK_THEME] = AppPrefs.darkTheme(context)
        p[ASK_PATH] = AppPrefs.askPath(context)
        p[WIFI_ONLY] = AppPrefs.wifiOnly(context)
        p[UNPACK] = AppPrefs.unpack(context)
        p[UPDATE_WIFI] = AppPrefs.updateWifiOnly(context)
        p[SHOW_PAGE] = AppPrefs.showPageButton(context)
        p[SHOW_FIND] = AppPrefs.showFindArchiveButton(context)
        p[AUTO_ARCHIVES] = AppPrefs.autoFindArchives(context)
        p[VERSION] = 2
    }

    suspend fun save(context: Context, value: ModernSettings) {
        context.applicationContext.audoibooDataStore.edit { p ->
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
            p[VERSION] = 2
            p[LEGACY_IMPORTED] = true
        }
    }

    suspend fun setWifiOnly(context: Context, value: Boolean) = context.applicationContext.audoibooDataStore.edit { it[WIFI_ONLY] = value }
    suspend fun setAutoFindArchives(context: Context, value: Boolean) = context.applicationContext.audoibooDataStore.edit { it[AUTO_ARCHIVES] = value }
    suspend fun setDarkTheme(context: Context, value: Boolean) = context.applicationContext.audoibooDataStore.edit { it[DARK_THEME] = value }
}
