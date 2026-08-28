package org.audoiboo.tracker

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.audoibooDataStore by preferencesDataStore(name = "audoiboo_settings")

data class ModernSettings(
    val wifiOnly: Boolean = false,
    val autoFindArchives: Boolean = true,
    val darkTheme: Boolean = false,
    val schemaVersion: Int = 1
)

object PreferenceDataStore {
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
            // Read through the existing public facade so migration follows the app's current semantics.
            p[WIFI_ONLY] = AppPrefs.wifiOnly(context)
            p[AUTO_ARCHIVES] = AppPrefs.autoFindArchives(context)
            p[DARK_THEME] = AppPrefs.darkTheme(context)
            p[VERSION] = 1
            p[LEGACY_IMPORTED] = true
        }
    }

    suspend fun setWifiOnly(context: Context, value: Boolean) = context.audoibooDataStore.edit { it[WIFI_ONLY] = value }
    suspend fun setAutoFindArchives(context: Context, value: Boolean) = context.audoibooDataStore.edit { it[AUTO_ARCHIVES] = value }
    suspend fun setDarkTheme(context: Context, value: Boolean) = context.audoibooDataStore.edit { it[DARK_THEME] = value }
}
