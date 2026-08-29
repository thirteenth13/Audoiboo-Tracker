package org.audoiboo.tracker

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupStore {
    private const val AUTO_PREFS = "automatic_backups"
    private const val LAST_AUTO = "last_auto_backup"
    private const val AUTO_PATH = "auto_backup_path"
    const val DEFAULT_AUTO_PATH = "/storage/emulated/0/Download/Audoiboo/"
    private val restoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun exportJson(context: Context): String = exportJson(context, true, true, true)

    fun exportJson(context: Context, includeSettings: Boolean, includeBookmarks: Boolean, includeStatistics: Boolean): String {
        val root = JSONObject()
        root.put("format", 7)
        root.put("createdAt", System.currentTimeMillis())
        val app = context.applicationContext
        val tracker = runCatching { runBlocking(Dispatchers.IO) { LibraryRepository.exportCompatJson(app) } }
            .getOrElse { context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", "[]").orEmpty() }
        root.put("tracker", tracker)
        runCatching { runBlocking(Dispatchers.IO) { LibraryRepository.exportTagsJson(app) } }.getOrNull()?.let { root.put("roomTags", it) }
        runCatching { runBlocking(Dispatchers.IO) { TrackPositionStore.exportJson(app) } }.getOrNull()?.let { root.put("roomTrackPositions", it) }
        runCatching { runBlocking(Dispatchers.IO) { PlaybackStateRepository.exportQueueJson(app) } }.getOrNull()?.let { root.put("roomPlaybackQueue", it) }
        runCatching { runBlocking(Dispatchers.IO) { PlaybackStateRepository.exportResumeJson(app) } }.getOrNull()?.let { root.put("roomPlaybackResume", it) }
        addSharedState(context, root, includeSettings, includeBookmarks, includeStatistics)
        return root.toString(2)
    }

    suspend fun exportJsonFromRoom(context: Context, includeSettings: Boolean = true, includeBookmarks: Boolean = true, includeStatistics: Boolean = true): String {
        val app = context.applicationContext
        val root = JSONObject()
        root.put("format", 7)
        root.put("createdAt", System.currentTimeMillis())
        root.put("tracker", LibraryRepository.exportCompatJson(app))
        root.put("roomTags", LibraryRepository.exportTagsJson(app))
        root.put("roomTrackPositions", TrackPositionStore.exportJson(app))
        root.put("roomPlaybackQueue", PlaybackStateRepository.exportQueueJson(app))
        PlaybackStateRepository.exportResumeJson(app)?.let { root.put("roomPlaybackResume", it) }
        addSharedState(context, root, includeSettings, includeBookmarks, includeStatistics)
        return root.toString(2)
    }

    fun importJson(context: Context, raw: String) {
        val root = JSONObject(raw)
        val tracker = root.optString("tracker", "[]")
        val roomTags = root.optJSONObject("roomTags")
        val trackPositions = root.optJSONObject("roomTrackPositions") ?: root.optJSONObject("playerPositions")
        val roomQueue = root.optJSONArray("roomPlaybackQueue") ?: legacyQueueFromBackup(root.optJSONObject("playerQueue"))
        val roomResume = root.optJSONObject("roomPlaybackResume") ?: legacyResumeFromBackup(root.optJSONObject("playerExtras"))
        context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putString("library", tracker).apply()
        restoreNonTrackerState(context, root)
        restoreScope.launch {
            runCatching {
                reconcileRestoredState(context.applicationContext, tracker, roomTags, trackPositions, roomQueue, roomResume)
                recoverAfterRestore(context.applicationContext)
            }
        }
    }

    suspend fun importJsonToRoom(context: Context, raw: String) {
        val root = JSONObject(raw)
        val tracker = root.optString("tracker", "[]")
        val roomTags = root.optJSONObject("roomTags")
        val trackPositions = root.optJSONObject("roomTrackPositions") ?: root.optJSONObject("playerPositions")
        val roomQueue = root.optJSONArray("roomPlaybackQueue") ?: legacyQueueFromBackup(root.optJSONObject("playerQueue"))
        val roomResume = root.optJSONObject("roomPlaybackResume") ?: legacyResumeFromBackup(root.optJSONObject("playerExtras"))
        context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putString("library", tracker).apply()
        restoreNonTrackerState(context, root)
        reconcileRestoredState(context.applicationContext, tracker, roomTags, trackPositions, roomQueue, roomResume)
        recoverAfterRestore(context)
    }

    private suspend fun reconcileRestoredState(
        context: Context,
        tracker: String,
        roomTags: JSONObject?,
        trackPositions: JSONObject?,
        roomQueue: JSONArray?,
        roomResume: JSONObject?
    ) {
        LibraryRepository.restoreLegacyJson(context, tracker)
        LibraryRepository.restoreTagsJson(context, roomTags)
        TrackPositionStore.restoreJson(context, trackPositions)
        PlaybackStateRepository.restoreRoomState(context, roomQueue, roomResume)
        PreferenceDataStore.syncFromLegacy(context)
        PlayerExtrasRoomSync.syncFromLegacy(context)
        RoomCoverSync.enqueueAll(context)
    }

    private fun addSharedState(context: Context, root: JSONObject, includeSettings: Boolean, includeBookmarks: Boolean, includeStatistics: Boolean) {
        root.put("downloads", context.getSharedPreferences("managed_downloads", Context.MODE_PRIVATE).getString("items", "[]"))
        root.put("playerLibrary", prefsToJson(context, "player_library"))
        if (includeSettings) {
            root.put("settings", prefsToJson(context, "app_settings"))
            root.put("playerSettings", prefsToJson(context, "player_settings"))
            root.put("audioEnhancement", prefsToJson(context, "audio_enhancement"))
            root.put("seriesAutomation", prefsToJson(context, "series_automation"))
            root.put("storageAccess", prefsToJson(context, "storage_access"))
        }
        if (includeBookmarks || includeStatistics) root.put("playerExtras", prefsToJson(context, "player_extras"))
        if (includeBookmarks) root.put("bookmarks", prefsToJson(context, "bookmarks"))
    }

    private fun restoreNonTrackerState(context: Context, root: JSONObject) {
        context.getSharedPreferences("managed_downloads", Context.MODE_PRIVATE).edit().putString("items", root.optString("downloads", "[]")).apply()
        root.optJSONObject("settings")?.let { jsonToPrefs(context, "app_settings", it) }
        root.optJSONObject("playerSettings")?.let { jsonToPrefs(context, "player_settings", it) }
        root.optJSONObject("audioEnhancement")?.let { jsonToPrefs(context, "audio_enhancement", it) }
        root.optJSONObject("seriesAutomation")?.let { jsonToPrefs(context, "series_automation", it) }
        root.optJSONObject("bookmarks")?.let { jsonToPrefs(context, "bookmarks", it) }
        root.optJSONObject("playerLibrary")?.let { jsonToPrefs(context, "player_library", it) }
        // Old backups are parsed into Room by legacyQueueFromBackup/legacyResumeFromBackup.
        root.optJSONObject("playerExtras")?.let { jsonToPrefs(context, "player_extras", it) }
    }

    private fun legacyQueueFromBackup(value: JSONObject?): JSONArray? {
        val raw = value?.optString("book_dirs")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONArray(raw) }.getOrNull()
    }

    private fun legacyResumeFromBackup(value: JSONObject?): JSONObject? {
        val raw = value?.optString("playback_snapshot")?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun recoverAfterRestore(context: Context) {
        DownloadScheduler.recover(context)
        SeriesAutomationPrefs.schedule(context)
        WebDavSync.schedule(context)
    }

    fun automaticBackupPath(context: Context): String = context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
        .getString(AUTO_PATH, DEFAULT_AUTO_PATH)?.trim()?.ifBlank { DEFAULT_AUTO_PATH } ?: DEFAULT_AUTO_PATH

    fun setAutomaticBackupPath(context: Context, path: String) {
        val normalized = path.trim().ifBlank { DEFAULT_AUTO_PATH }.let { if (it.endsWith('/')) it else "$it/" }
        context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).edit().putString(AUTO_PATH, normalized).apply()
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
        restoreScope.launch {
            runCatching {
                val requested = automaticBackupPath(context)
                val dir = File(requested).apply { mkdirs() }
                val writableDir = if (dir.exists() && dir.canWrite()) dir else File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "AudoibooBackups").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date(now))
                File(writableDir, "Audoiboo-auto-$stamp.json").writeText(exportJsonFromRoom(context, settings, bookmarks, statistics))
                writableDir.listFiles()?.filter { it.name.startsWith("Audoiboo-auto-") }?.sortedByDescending { it.lastModified() }?.drop(7)?.forEach { it.delete() }
                p.edit().putLong(LAST_AUTO, now).apply()
            }
        }
    }

    fun automaticSettings(context: Context): Triple<Boolean, Boolean, Boolean> {
        val p = context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE)
        return Triple(p.getBoolean("settings", true), p.getBoolean("bookmarks", true), p.getBoolean("statistics", true))
    }

    fun setAutomaticSettings(context: Context, settings: Boolean, bookmarks: Boolean, statistics: Boolean) {
        context.getSharedPreferences(AUTO_PREFS, Context.MODE_PRIVATE).edit().putBoolean("settings", settings).putBoolean("bookmarks", bookmarks).putBoolean("statistics", statistics).apply()
    }

    private fun prefsToJson(context: Context, name: String): JSONObject {
        val out = JSONObject()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) -> when (v) { is Boolean, is Int, is Long, is Float, is String -> out.put(k, v) } }
        return out
    }

    private fun jsonToPrefs(context: Context, name: String, obj: JSONObject) {
        val e = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            when (val v = obj.get(k)) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); is Double -> e.putFloat(k, v.toFloat()); is String -> e.putString(k, v) }
        }
        e.apply()
    }
}
