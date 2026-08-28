package org.audoiboo.tracker

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupStore {
    private const val AUTO_PREFS = "automatic_backups"
    private const val LAST_AUTO = "last_auto_backup"

    fun exportJson(context: Context): String = exportJson(context, true, true, true)

    fun exportJson(context: Context, includeSettings: Boolean, includeBookmarks: Boolean, includeStatistics: Boolean): String {
        val root = JSONObject()
        root.put("format", 2)
        root.put("createdAt", System.currentTimeMillis())
        root.put("tracker", context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", "[]"))
        root.put("downloads", context.getSharedPreferences("managed_downloads", Context.MODE_PRIVATE).getString("items", "[]"))
        root.put("playerLibrary", prefsToJson(context, "player_library"))
        if (includeSettings) {
            root.put("settings", prefsToJson(context, "app_settings"))
            root.put("playerSettings", prefsToJson(context, "player_settings"))
        }
        if (includeBookmarks) root.put("bookmarks", prefsToJson(context, "bookmarks"))
        if (includeStatistics) root.put("playerPositions", prefsToJson(context, "player_positions"))
        return root.toString(2)
    }

    fun importJson(context: Context, raw: String) {
        val root = JSONObject(raw)
        context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit()
            .putString("library", root.optString("tracker", "[]")).apply()
        context.getSharedPreferences("managed_downloads", Context.MODE_PRIVATE).edit()
            .putString("items", root.optString("downloads", "[]")).apply()
        root.optJSONObject("settings")?.let { jsonToPrefs(context, "app_settings", it) }
        root.optJSONObject("playerSettings")?.let { jsonToPrefs(context, "player_settings", it) }
        root.optJSONObject("bookmarks")?.let { jsonToPrefs(context, "bookmarks", it) }
        root.optJSONObject("playerPositions")?.let { jsonToPrefs(context, "player_positions", it) }
        root.optJSONObject("playerLibrary")?.let { jsonToPrefs(context, "player_library", it) }
    }

    fun maybeCreateDailyBackup(context: Context) {
        val p = context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
        val settings = p.getBoolean("settings", true)
        val bookmarks = p.getBoolean("bookmarks", true)
        val statistics = p.getBoolean("statistics", true)
        if (!settings && !bookmarks && !statistics) return
        val now = System.currentTimeMillis()
        val last = p.getLong(LAST_AUTO, 0L)
        if (now - last < 24L * 60L * 60L * 1000L) return
        runCatching {
            val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            val dir = File(root, "AudoibooBackups").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(now))
            File(dir, "Audoiboo-auto-$stamp.json").writeText(exportJson(context, settings, bookmarks, statistics))
            dir.listFiles()?.filter { it.name.startsWith("Audoiboo-auto-") }?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
            p.edit().putLong(LAST_AUTO, now).apply()
        }
    }

    fun automaticSettings(context: Context): Triple<Boolean, Boolean, Boolean> {
        val p = context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
        return Triple(p.getBoolean("settings", true), p.getBoolean("bookmarks", true), p.getBoolean("statistics", true))
    }

    fun setAutomaticSettings(context: Context, settings: Boolean, bookmarks: Boolean, statistics: Boolean) {
        context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("settings", settings)
            .putBoolean("bookmarks", bookmarks)
            .putBoolean("statistics", statistics)
            .apply()
    }

    private fun prefsToJson(context: Context, name: String): JSONObject {
        val out = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
            when (v) {
                is Boolean, is Int, is Long, is Float, is String -> out.put(k, v)
            }
        }
        return out
    }

    private fun jsonToPrefs(context: Context, name: String, obj: JSONObject) {
        val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = obj.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, v)
            }
        }
        e.apply()
    }
}
