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
    private const val LAST_DIR = "last_book_dir"
    private const val LAST_TITLE = "last_book_title"
    private const val LAST_URI = "last_uri"
    private const val LAST_AT = "last_at"
    private const val LISTENED_MS = "listened_ms"
    private const val BOOKMARKS = "bookmarks_v2"
    private const val SNAPSHOT = "playback_snapshot"
    private const val SPEEDS = "book_speeds"
    private const val BROKEN = "broken_uris"
    private const val DAILY = "daily_listened"
    private const val TAGS = "book_tags"
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
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val uriText = uri?.toString().orEmpty()
        p.edit().putString(LAST_DIR, dir).putString(LAST_TITLE, title).putString(LAST_URI, uriText).putLong(LAST_AT, now).apply()
        val history = history(context).toMutableList().apply {
            removeAll { it.dir == dir }
            add(0, HistoryItem(dir, title, now))
        }.take(50)
        val arr = JSONArray(); history.forEach { arr.put(JSONObject().put("dir", it.dir).put("title", it.title).put("at", it.at)) }
        p.edit().putString(HISTORY, arr.toString()).apply()

        if (uriText.isNotBlank()) {
            PlayerLibrary.all(context).firstOrNull { it.uri == uriText }?.series?.takeIf { it.isNotBlank() }?.let { series ->
                saveSeriesResume(context, SeriesResume(series, dir, title, uriText, now))
            }
        }
    }

    fun resume(context: Context): Resume? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dir = p.getString(LAST_DIR, null)?.takeIf { it.isNotBlank() } ?: return null
        return Resume(dir, p.getString(LAST_TITLE, dir).orEmpty(), p.getString(LAST_URI, "").orEmpty(), p.getLong(LAST_AT, 0L))
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
        val q = JSONArray(); queue.distinct().forEach(q::put)
        val o = JSONObject()
            .put("dir", dir).put("title", title).put("uri", uri?.toString().orEmpty())
            .put("fileIndex", fileIndex).put("positionMs", positionMs.coerceAtLeast(0L))
            .put("queue", q).put("updatedAt", System.currentTimeMillis())
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SNAPSHOT, o.toString()).apply()
    }

    fun snapshot(context: Context): PlaybackSnapshot? = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SNAPSHOT, null) ?: return null
        val o = JSONObject(raw); val q = o.optJSONArray("queue") ?: JSONArray()
        PlaybackSnapshot(
            o.optString("dir"), o.optString("title"), o.optString("uri"), o.optInt("fileIndex"),
            o.optLong("positionMs"), (0 until q.length()).mapNotNull { q.optString(it).takeIf(String::isNotBlank) }, o.optLong("updatedAt")
        ).takeIf { it.dir.isNotBlank() }
    }.getOrNull()

    fun history(context: Context): List<HistoryItem> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HISTORY, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { HistoryItem(it.optString("dir"), it.optString("title"), it.optLong("at")) } }.filter { it.dir.isNotBlank() }
    }.getOrDefault(emptyList())

    fun addListened(context: Context, deltaMs: Long) {
        if (deltaMs <= 0 || deltaMs > 10_000) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putLong(LISTENED_MS, p.getLong(LISTENED_MS, 0L) + deltaMs).apply()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val daily = runCatching { JSONObject(p.getString(DAILY, "{}")) }.getOrElse { JSONObject() }
        daily.put(day, daily.optLong(day) + deltaMs)
        val keys = daily.keys().asSequence().toList().sortedDescending()
        keys.drop(120).forEach { daily.remove(it) }
        p.edit().putString(DAILY, daily.toString()).apply()
    }

    fun totalListenedMs(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(LISTENED_MS, 0L)
    fun dailyListened(context: Context): Map<String, Long> = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DAILY, "{}"))
        o.keys().asSequence().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())

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
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SPEEDS, "{}")
        return runCatching { JSONObject(raw).optDouble(dir, 1.0).toFloat().coerceIn(.5f, 3f) }.getOrDefault(1f)
    }

    fun setSpeed(context: Context, dir: String, speed: Float) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(p.getString(SPEEDS, "{}")) }.getOrElse { JSONObject() }
        o.put(dir, speed.coerceIn(.5f, 3f).toDouble())
        p.edit().putString(SPEEDS, o.toString()).apply()
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
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val o = runCatching { JSONObject(p.getString(TAGS, "{}")) }.getOrElse { JSONObject() }
        val a = JSONArray(); tags.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach(a::put)
        o.put(dir, a); p.edit().putString(TAGS, o.toString()).apply()
    }

    fun tags(context: Context, dir: String): List<String> = runCatching {
        val o = JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TAGS, "{}")); val a = o.optJSONArray(dir) ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    }.getOrDefault(emptyList())

    fun addBookmark(context: Context, uri: Uri, position: Long, note: String) {
        val list = bookmarks(context).toMutableList()
        list.add(0, Bookmark(uri.toString(), position, note.trim(), System.currentTimeMillis()))
        saveBookmarks(context, list.take(500))
    }

    fun bookmarks(context: Context): List<Bookmark> = runCatching {
        val a = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(BOOKMARKS, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Bookmark(it.optString("uri"), it.optLong("position"), it.optString("note"), it.optLong("createdAt")) } }
    }.getOrDefault(emptyList())

    fun deleteBookmark(context: Context, createdAt: Long) = saveBookmarks(context, bookmarks(context).filterNot { it.createdAt == createdAt })

    private fun saveBookmarks(context: Context, list: List<Bookmark>) {
        val a = JSONArray(); list.forEach { a.put(JSONObject().put("uri", it.uri).put("position", it.position).put("note", it.note).put("createdAt", it.createdAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(BOOKMARKS, a.toString()).apply()
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
