package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Persistent audiobook-specific state shared by the player UI and playback service. */
internal object PlayerExtras {
    private const val PREFS = "player_extras"
    private const val HISTORY = "history"
    private const val LISTENED_MS = "listened_ms"
    private const val BOOKMARKS = "bookmarks_v2"
    private const val DAILY = "daily_listened"

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
        PlayerStateStore.saveSeriesResume(
            context,
            SeriesResumeEntity(value.series, value.dir, value.title, value.uri, value.at)
        )
    }

    fun seriesResume(context: Context, series: String): SeriesResume? =
        PlayerStateStore.seriesResume(context, series)?.let {
            SeriesResume(it.series, it.dir, it.title, it.uri, it.at)
        }

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
        PlayerStateStore.bookSpeed(context, dir)?.let { return it }
        val normalizedDir = dir.replace('\\', '/').trimEnd('/')
        val series = PlayerLibrary.all(context).firstOrNull {
            it.relativePath.replace('\\', '/').trimEnd('/') == normalizedDir
        }?.series?.takeIf { it.isNotBlank() } ?: return 1f
        return seriesSpeedFor(context, series)
    }

    fun setSpeed(context: Context, dir: String, speed: Float) {
        PlayerStateStore.setBookSpeed(context, dir, speed)
    }

    fun clearBookSpeed(context: Context, dir: String) {
        PlayerStateStore.clearBookSpeed(context, dir)
    }

    fun seriesSpeedFor(context: Context, series: String): Float {
        if (series.isBlank()) return 1f
        return PlayerStateStore.seriesSpeed(context, series) ?: 1f
    }

    fun setSeriesSpeed(context: Context, series: String, speed: Float) {
        PlayerStateStore.setSeriesSpeed(context, series, speed)
    }

    fun markBroken(context: Context, uri: Uri) {
        PlayerStateStore.markBroken(context, uri.toString())
    }

    fun clearBroken(context: Context) {
        PlayerStateStore.clearBroken(context)
    }

    fun brokenUris(context: Context): Set<String> = PlayerStateStore.brokenUris(context)

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
