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
        root.put("format", BackupFormatPolicy.CURRENT_FORMAT)
        root.put("createdAt", System.currentTimeMillis())
        val app = context.applicationContext
        val tracker = runCatching { runBlocking(Dispatchers.IO) { LibraryRepository.exportCompatJson(app) } }
            .getOrElse { context.getSharedPreferences("tracker", Context.MODE_PRIVATE).getString("library", "[]").orEmpty() }
        root.put("tracker", tracker)
        runCatching { runBlocking(Dispatchers.IO) { LibraryRepository.exportTagsJson(app) } }.getOrNull()?.let { root.put("roomTags", it) }
        runCatching { runBlocking(Dispatchers.IO) { TrackPositionStore.exportJson(app) } }.getOrNull()?.let { root.put("roomTrackPositions", it) }
        runCatching { runBlocking(Dispatchers.IO) { PlaybackStateRepository.exportQueueJson(app) } }.getOrNull()?.let { root.put("roomPlaybackQueue", it) }
        runCatching { runBlocking(Dispatchers.IO) { PlaybackStateRepository.exportResumeJson(app) } }.getOrNull()?.let { root.put("roomPlaybackResume", it) }
        runCatching { runBlocking(Dispatchers.IO) { PlayerStateStore.exportJson(app) } }.getOrNull()?.let { root.put("roomPlayerState", it) }
        runCatching { ManagedDownloadRoomStore.exportJson(app) }.getOrNull()?.let { root.put("downloads", it) }
        if (includeBookmarks || includeStatistics) {
            runCatching { runBlocking(Dispatchers.IO) { PlayerExtrasRepository.exportJson(app) } }.getOrNull()?.let { root.put("roomPlayerExtras", it) }
        }
        if (includeSettings) {
            runCatching { runBlocking(Dispatchers.IO) { PreferenceDataStore.exportJson(app) } }.getOrNull()?.let { root.put("settings", it) }
        }
        addSharedState(context, root, includeSettings, includeBookmarks)
        return root.toString(2)
    }

    suspend fun exportJsonFromRoom(context: Context, includeSettings: Boolean = true, includeBookmarks: Boolean = true, includeStatistics: Boolean = true): String {
        val app = context.applicationContext
        val root = JSONObject()
        root.put("format", BackupFormatPolicy.CURRENT_FORMAT)
        root.put("createdAt", System.currentTimeMillis())
        root.put("tracker", LibraryRepository.exportCompatJson(app))
        root.put("roomTags", LibraryRepository.exportTagsJson(app))
        root.put("roomTrackPositions", TrackPositionStore.exportJson(app))
        root.put("roomPlaybackQueue", PlaybackStateRepository.exportQueueJson(app))
        PlaybackStateRepository.exportResumeJson(app)?.let { root.put("roomPlaybackResume", it) }
        root.put("roomPlayerState", PlayerStateStore.exportJson(app))
        root.put("downloads", ManagedDownloadRoomStore.exportJson(app))
        if (includeBookmarks || includeStatistics) root.put("roomPlayerExtras", PlayerExtrasRepository.exportJson(app))
        if (includeSettings) root.put("settings", PreferenceDataStore.exportJson(app))
        addSharedState(context, root, includeSettings, includeBookmarks)
        return root.toString(2)
    }

    fun importJson(context: Context, raw: String) {
        val root = validatedRoot(raw)
        val tracker = root.getString("tracker")
        context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putString("library", tracker).apply()
        restoreNonTrackerState(context, root)
        restoreScope.launch {
            runCatching {
                restoreRoomState(context.applicationContext, tracker, root)
                recoverAfterRestore(context.applicationContext)
            }
        }
    }

    suspend fun importJsonToRoom(context: Context, raw: String) {
        val root = validatedRoot(raw)
        val tracker = root.getString("tracker")
        context.getSharedPreferences("tracker", Context.MODE_PRIVATE).edit().putString("library", tracker).apply()
        restoreNonTrackerState(context, root)
        restoreRoomState(context.applicationContext, tracker, root)
        recoverAfterRestore(context)
    }

    private fun validatedRoot(raw: String): JSONObject {
        val root = JSONObject(raw)
        val trackerValue = root.opt("tracker")
        val tracker = trackerValue as? String
        val trackerValid = tracker != null && LibraryRepository.isValidLegacyJson(tracker)
        val format = if (root.has("format") && !root.isNull("format")) root.optInt("format", Int.MIN_VALUE) else null
        val downloadsValue = if (root.has("downloads")) root.opt("downloads") else null
        val downloadsValid = downloadsValue == null || ManagedDownloadRoomStore.isValidBackupPayload(downloadsValue)
        BackupFormatPolicy.validate(
            format = format,
            hasTracker = root.has("tracker"),
            trackerIsValidArray = trackerValid,
            hasDownloads = root.has("downloads"),
            downloadsAreValid = downloadsValid
        )?.let { throw IllegalArgumentException(it) }
        return root
    }

    /**
     * Series tracking remains backward-compatible through the legacy tracker JSON.
     * Player data is restored only from current Room-native fields.
     */
    private suspend fun restoreRoomState(context: Context, tracker: String, root: JSONObject) {
        LibraryRepository.restoreLegacyJson(context, tracker)
        root.optJSONObject("roomTags")?.let { LibraryRepository.restoreTagsJson(context, it) }
        PlayerTagStore.refresh(context)

        TrackPositionStore.restoreJson(context, root.optJSONObject("roomTrackPositions"))
        PlaybackStateRepository.restoreRoomState(
            context,
            root.optJSONArray("roomPlaybackQueue"),
            root.optJSONObject("roomPlaybackResume")
        )
        root.optJSONObject("roomPlayerState")?.let { PlayerStateStore.restoreJson(context, it) }
        root.optJSONObject("roomPlayerExtras")?.let { PlayerExtrasRepository.restoreJson(context, it) }
        PlayerExtrasStore.refresh(context)
        root.optJSONObject("settings")?.let { PreferenceDataStore.restoreJson(context, it) }
        if (root.has("downloads")) ManagedDownloadRoomStore.restoreJson(context, root.opt("downloads"))
        RoomCoverSync.enqueueAll(context)
    }

    private fun addSharedState(context: Context, root: JSONObject, includeSettings: Boolean, includeBookmarks: Boolean) {
        root.put("playerLibrary", prefsToJson(context, "player_library"))
        if (includeSettings) {
            root.put("playerSettings", prefsToJson(context, "player_settings"))
            root.put("audioEnhancement", prefsToJson(context, "audio_enhancement"))
            root.put("seriesAutomation", prefsToJson(context, "series_automation"))
            root.put("storageAccess", prefsToJson(context, "storage_access"))
        }
        if (includeBookmarks) root.put("bookmarks", prefsToJson(context, "bookmarks"))
    }

    private fun restoreNonTrackerState(context: Context, root: JSONObject) {
        root.optJSONObject("playerSettings")?.let { jsonToPrefs(context, "player_settings", it) }
        root.optJSONObject("audioEnhancement")?.let { jsonToPrefs(context, "audio_enhancement", it) }
        root.optJSONObject("seriesAutomation")?.let { jsonToPrefs(context, "series_automation", it) }
        root.optJSONObject("bookmarks")?.let { jsonToPrefs(context, "bookmarks", it) }
        root.optJSONObject("playerLibrary")?.let { jsonToPrefs(context, "player_library", it) }
        root.optJSONObject("storageAccess")?.let { jsonToPrefs(context, "storage_access", it) }
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
