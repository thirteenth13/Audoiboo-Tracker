package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Persistent audiobook-specific state shared by the player UI and playback service. */
internal object PlayerExtras {
    private const val PREFS = "player_extras"
    private const val HISTORY = "history"
    private const val LISTENED_MS = "listened_ms"
    private const val BOOKMARKS = "bookmarks_v2"
    private const val SPEEDS = "book_speeds"
    private const val SERIES_SPEEDS = "series_speeds"
    private const val BROKEN = "broken_uris"
    private const val DAILY = "daily_listened"
    private const val SERIES_RESUME = "series_resume"

    data class Resume(val dir: String, val title: String, val uri: String, val at: Long)
    data class SeriesResume(val series: String, val dir: String, val title: String, val uri: String, val at: Long)
    data class HistoryItem(val dir: String, val title: String, val at: Long)
    data class Bookmark(val uri: String, val position: Long, val note: String, val createdAt: Long)
    data class PlaybackSnapshot(
        val dir: String,
        val title: String,
        val uri: String,
        val fileIndex: Int,
        val positionMs: Long,
        val queue: List<String>,
        val updatedAt: Long
    )

    fun rememberBook(context: Context, dir: String, title: String, uri: Uri?) {
        val now = System.currentTimeMillis()
        val uriText = uri?.toString().orEmpty()
        PlayerExtrasStore.rememberBook(context, dir, title, now)
        PlaybackResumeStore.rememberBook(context, dir, title, uriText)

        if (uriText.isNotBlank()) {
            PlayerLibrary.all(context).firstOrNull { it.uri == uriText }?.series?.takeIf { it.isNotBlank() }?.let { series ->
                saveSeriesResume(context, SeriesResume(series, dir, title, uriText, now))
            }
        }
    }

    fun resume(context: Context): Resume? = PlaybackResumeStore.current(context)?.let {
        Resume(it.dir, it.title, it.uri, it.updatedAt)
    }

    private fun saveSeriesResume(context: Context, value: SeriesResume) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(p.getString(SERIES_RESUME, "{}")) }.getOrElse { JSONObject() }
        root.put(value.series, JSONObject()
            .put("dir", value.dir)
            .put("title", value.title)
            .put("uri", value.uri)
            .put("at", value.at))
        p.edit().putString(SERIES_RESUME, root.toString()).apply()
    }

    fun seriesResume(context: Context, series: String): SeriesResume? = runCatching {
        val root = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SERIES_RESUME, "{}"))
        val o = root.optJSONObject(series) ?: return null
        val dir = o.optString("dir").takeIf { it.isNotBlank() } ?: return null
        SeriesResume(series, dir, o.optString("title", dir), o.optString("uri"), o.optLong("at"))
    }.getOrNull()

    fun saveSnapshot(context: Context, dir: String, title: String, uri: Uri?, fileIndex: Int, positionMs: Long, queue: List<String>) {
        if (dir.isBlank()) return
        PlaybackResumeStore.save(
            context,
            PlaybackStateRepository.Snapshot(
                dir = dir,
                title = title,
                uri = uri?.toString().orEmpty(),
                fileIndex = fileIndex.coerceAtLeast(0),
                positionMs = positionMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    fun snapshot(context: Context): PlaybackSnapshot? {
        val value = PlaybackResumeStore.current(context) ?: return null
        return PlaybackSnapshot(
            dir = value.dir,
            title = value.title,
            uri = value.uri,
            fileIndex = value.fileIndex,
            positionMs = value.positionMs,
            queue = PlaybackQueueStore.current(context),
            updatedAt = value.updatedAt
        )
    }

    fun history(context: Context): List<HistoryItem> {
        PlayerExtrasStore.initialize(context)
        if (PlayerExtrasStore.isReady()) {
            return PlayerExtrasStore.history().map { HistoryItem(it.dir, it.title, it.at) }
        }
        return legacyHistory(context)
    }

    private fun legacyHistory(context: Context): List<HistoryItem> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HISTORY, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { HistoryItem(it.optString("dir"), it.optString("title"), it.optLong("at")) } }.filter { it.dir.isNotBlank() }
    }.getOrDefault(emptyList())

    fun addListened(context: Context, deltaMs: Long) {
        PlayerExtrasStore.addListened(context, deltaMs)
    }

    fun totalListenedMs(context: Context): Long {
        PlayerExtrasStore.initialize(context)
        return if (PlayerExtrasStore.isReady()) PlayerExtrasStore.totalMs()
        else context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LISTENED_MS, 0L)
    }

    fun dailyListened(context: Context): Map<String, Long> {
        PlayerExtrasStore.initialize(context)
        if (PlayerExtrasStore.isReady()) return PlayerExtrasStore.daily().associate { it.day to it.listenedMs }
        return runCatching {
            val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DAILY, "{}"))
            o.keys().asSequence().associateWith { o.optLong(it) }
        }.getOrDefault(emptyMap())
    }

    fun smartRewindMs(lastPlayedAt: Long): Long {
        val gap = (System.currentTimeMillis() - lastPlayedAt).coerceAtLeast(0L)
        return when {
            gap >= 24 * 60 * 60_000L -> 60_000L
            gap >= 60 * 60_000L -> 30_000L
            gap >= 5 * 60_000L -> 10_000L
            else -> 0L
        }
    }

    fun speedFor(context: Context, dir: String?): Float {
        if (dir.isNullOrBlank()) return 1f
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bookSpeeds = runCatching { JSONObject(p.getString(SPEEDS, "{}")) }.getOrElse { JSONObject() }
        if (bookSpeeds.has(dir)) return bookSpeeds.optDouble(dir, 1.0).toFloat().coerceIn(.5f, 3f)
        val normalizedDir = dir.replace('\\', '/').trimEnd('/')
        val series = PlayerLibrary.all(context).firstOrNull {
            it.relativePath.replace('\\', '/').trimEnd('/') == normalizedDir
        }?.series?.takeIf { it.isNotBlank() } ?: return 1f
        return seriesSpeedFor(context, series)
    }

    fun setSpeed(context: Context, dir: String, speed: Float) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(p.getString(SPEEDS, "{}")) }.getOrElse { JSONObject() }
        o.put(dir, speed.coerceIn(.5f, 3f).toDouble())
        p.edit().putString(SPEEDS, o.toString()).apply()
    }

    fun clearBookSpeed(context: Context, dir: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(p.getString(SPEEDS, "{}")) }.getOrElse { JSONObject() }
        o.remove(dir)
        p.edit().putString(SPEEDS, o.toString()).apply()
    }

    fun seriesSpeedFor(context: Context, series: String): Float {
        if (series.isBlank()) return 1f
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SERIES_SPEEDS, "{}")
        return runCatching { JSONObject(raw).optDouble(series, 1.0).toFloat().coerceIn(.5f, 3f) }.getOrDefault(1f)
    }

    fun setSeriesSpeed(context: Context, series: String, speed: Float) {
        if (series.isBlank()) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(p.getString(SERIES_SPEEDS, "{}")) }.getOrElse { JSONObject() }
        o.put(series, speed.coerceIn(.5f, 3f).toDouble())
        p.edit().putString(SERIES_SPEEDS, o.toString()).apply()
    }

    fun markBroken(context: Context, uri: Uri) {
        val set = brokenUris(context).toMutableSet().apply { add(uri.toString()) }
        val a = JSONArray(); set.forEach(a::put)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(BROKEN, a.toString()).apply()
    }

    fun clearBroken(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(BROKEN).apply()

    fun brokenUris(context: Context): Set<String> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BROKEN, "[]"))
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.getOrDefault(emptySet())

    fun setTags(context: Context, dir: String, tags: List<String>) {
        PlayerTagStore.setTags(context, dir, tags)
    }

    fun tags(context: Context, dir: String): List<String> = PlayerTagStore.tags(context, dir)

    fun addBookmark(context: Context, uri: Uri, position: Long, note: String) {
        PlayerExtrasStore.addBookmark(context, uri.toString(), position, note)
    }

    fun bookmarks(context: Context): List<Bookmark> {
        PlayerExtrasStore.initialize(context)
        if (PlayerExtrasStore.isReady()) {
            return PlayerExtrasStore.bookmarks().map { Bookmark(it.uri, it.positionMs, it.note, it.createdAt) }
        }
        return legacyBookmarks(context)
    }

    private fun legacyBookmarks(context: Context): List<Bookmark> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BOOKMARKS, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Bookmark(it.optString("uri"), it.optLong("position"), it.optString("note"), it.optLong("createdAt")) } }
    }.getOrDefault(emptyList())

    fun deleteBookmark(context: Context, createdAt: Long) {
        PlayerExtrasStore.deleteBookmark(context, createdAt)
    }
}

internal object PlayerQueueActions {
    fun playNext(current: List<String>, activeDir: String?, dir: String): List<String> {
        val base = current.filterNot { it == dir }.toMutableList()
        val index = activeDir?.let { base.indexOf(it) } ?: -1
        base.add(if (index >= 0) index + 1 else 0, dir)
        return base
    }

    fun afterSeries(current: List<String>, activeDir: String?, currentSeriesDirs: List<String>, dir: String): List<String> {
        val base = current.filterNot { it == dir }.toMutableList()
        val seriesSet = currentSeriesDirs.toSet()
        val activeIndex = activeDir?.let { base.indexOf(it) } ?: -1
        val lastSeriesIndex = base.indices.lastOrNull { base[it] in seriesSet } ?: activeIndex
        base.add((lastSeriesIndex + 1).coerceIn(0, base.size), dir)
        return base
    }

    fun move(current: List<String>, from: Int, to: Int): List<String> {
        if (from !in current.indices || to !in current.indices || from == to) return current
        return current.toMutableList().apply { add(to, removeAt(from)) }
    }
}
