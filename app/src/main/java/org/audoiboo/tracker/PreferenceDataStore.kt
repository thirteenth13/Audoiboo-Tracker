package org.audoiboo.tracker

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.audoibooDataStore by preferencesDataStore(name = "audoiboo_settings")

data class ModernSettings(
    val wifiOnly: Boolean = false,
    val autoFindArchives: Boolean = true,
    val darkTheme: Boolean = false,
    val schemaVersion: Int = 1
)

object PreferenceDataStore {
    private const val LEGACY_FILE = "app_settings"
    private val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    private val AUTO_ARCHIVES = booleanPreferencesKey("auto_find_archives")
    private val DARK_THEME = booleanPreferencesKey("dark_theme")
    private val VERSION = intPreferencesKey("schema_version")
    private val LEGACY_IMPORTED = booleanPreferencesKey("legacy_imported")

    fun observe(context: Context): Flow<ModernSettings> = context.audoibooDataStore.data.map { p ->
        ModernSettings(
            wifiOnly = p[WIFI_ONLY] ?: false,
            autoFindArchives = p[AUTO_ARCHIVES] ?: true,
            darkTheme = p[DARK_THEME] ?: false,
            schemaVersion = p[VERSION] ?: 1
        )
    }

    suspend fun importLegacyIfNeeded(context: Context) {
        context.audoibooDataStore.edit { p ->
            if (p[LEGACY_IMPORTED] == true) return@edit
            copyLegacy(context, p)
            p[LEGACY_IMPORTED] = true
        }
    }

    suspend fun syncFromLegacy(context: Context) {
        context.audoibooDataStore.edit { p ->
            copyLegacy(context, p)
            p[LEGACY_IMPORTED] = true
        }
    }

    /** Prefer existing legacy values during migration, but recover them from DataStore if the old file is gone. */
    suspend fun reconcile(context: Context) {
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            syncFromLegacy(context)
            return
        }
        val p = context.audoibooDataStore.data.first()
        if (p[LEGACY_IMPORTED] != true) {
            importLegacyIfNeeded(context)
            return
        }
        legacy.edit()
            .putBoolean("wifi_only", p[WIFI_ONLY] ?: false)
            .putBoolean("auto_find_archives", p[AUTO_ARCHIVES] ?: true)
            .putBoolean("dark_theme", p[DARK_THEME] ?: false)
            .apply()
    }

    private fun copyLegacy(context: Context, p: MutablePreferences) {
        p[WIFI_ONLY] = AppPrefs.wifiOnly(context)
        p[AUTO_ARCHIVES] = AppPrefs.autoFindArchives(context)
        p[DARK_THEME] = AppPrefs.darkTheme(context)
        p[VERSION] = 1
    }

    suspend fun setWifiOnly(context: Context, value: Boolean) = context.audoibooDataStore.edit { it[WIFI_ONLY] = value }
    suspend fun setAutoFindArchives(context: Context, value: Boolean) = context.audoibooDataStore.edit { it[AUTO_ARCHIVES] = value }
    suspend fun setDarkTheme(context: Context, value: Boolean) = context.audoibooDataStore.edit { it[DARK_THEME] = value }
}
