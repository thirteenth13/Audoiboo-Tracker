package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Room-native live cache for player history, bookmarks and listening statistics.
 * SharedPreferences is used only by the one-time legacy importer.
 */
internal object PlayerExtrasStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyState = MutableStateFlow<List<PlaybackHistoryEntity>>(emptyList())
    private val bookmarksState = MutableStateFlow<List<PlayerBookmarkEntity>>(emptyList())
    private val dailyState = MutableStateFlow<List<DailyListeningEntity>>(emptyList())
    private val totalState = MutableStateFlow(0L)

    @Volatile private var initialized = false
    @Volatile private var ready = false

    fun isReady(): Boolean = ready
    fun history(): List<PlaybackHistoryEntity> = historyState.value
    fun bookmarks(): List<PlayerBookmarkEntity> = bookmarksState.value
    fun daily(): List<DailyListeningEntity> = dailyState.value
    fun totalMs(): Long = totalState.value
    fun observeHistory(): StateFlow<List<PlaybackHistoryEntity>> = historyState.asStateFlow()
    fun observeBookmarks(): StateFlow<List<PlayerBookmarkEntity>> = bookmarksState.asStateFlow()
    fun observeDaily(): StateFlow<List<DailyListeningEntity>> = dailyState.asStateFlow()
    fun observeTotal(): StateFlow<Long> = totalState.asStateFlow()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            PlayerExtrasRoomSync.syncFromLegacy(app)
            refresh(app)
            ready = true
            val dao = AudoibooDatabase.get(app).libraryDao()
            launch { dao.observePlaybackHistory().collect { historyState.value = it } }
            launch { dao.observePlayerBookmarks().collect { bookmarksState.value = it } }
            launch { dao.observeDailyListening().collect { dailyState.value = it } }
        }
    }

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        val dao = AudoibooDatabase.get(app).libraryDao()
        historyState.value = dao.playbackHistory()
        bookmarksState.value = dao.playerBookmarks()
        dailyState.value = dao.dailyListening()
        totalState.value = dao.listeningTotal()?.listenedMs ?: 0L
    }

    fun rememberBook(context: Context, dir: String, title: String, at: Long = System.currentTimeMillis()) {
        if (dir.isBlank()) return
        initialize(context)
        val row = PlaybackHistoryEntity(dir, title.ifBlank { dir }, at)
        historyState.value = (listOf(row) + historyState.value.filterNot { it.dir == dir }).take(50)
        val app = context.applicationContext
        scope.launch {
            val db = AudoibooDatabase.get(app)
            db.libraryDao().upsertPlaybackHistory(listOf(row))
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM playback_history WHERE dir NOT IN (SELECT dir FROM playback_history ORDER BY at DESC LIMIT 50)"
            )
        }
    }

    fun addBookmark(context: Context, uri: String, positionMs: Long, note: String, createdAt: Long = System.currentTimeMillis()) {
        if (uri.isBlank()) return
        initialize(context)
        val position = positionMs.coerceAtLeast(0L)
        val row = PlayerBookmarkEntity(
            id = bookmarkId(uri, position, createdAt),
            uri = uri,
            positionMs = position,
            note = note.trim(),
            createdAt = createdAt
        )
        bookmarksState.value = (listOf(row) + bookmarksState.value.filterNot { it.id == row.id }).take(500)
        val app = context.applicationContext
        scope.launch {
            val db = AudoibooDatabase.get(app)
            db.libraryDao().upsertPlayerBookmarks(listOf(row))
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM player_bookmarks WHERE id NOT IN (SELECT id FROM player_bookmarks ORDER BY createdAt DESC LIMIT 500)"
            )
        }
    }

    fun deleteBookmark(context: Context, createdAt: Long) {
        initialize(context)
        bookmarksState.value = bookmarksState.value.filterNot { it.createdAt == createdAt }
        val app = context.applicationContext
        scope.launch {
            AudoibooDatabase.get(app).openHelper.writableDatabase.execSQL(
                "DELETE FROM player_bookmarks WHERE createdAt = ?",
                arrayOf(createdAt)
            )
        }
    }

    fun addListened(context: Context, deltaMs: Long) {
        if (deltaMs <= 0L || deltaMs > 10_000L) return
        initialize(context)
        val delta = deltaMs.coerceAtLeast(0L)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        totalState.value = totalState.value + delta
        val updatedDaily = dailyState.value.associateBy { it.day }.toMutableMap()
        val current = updatedDaily[day]?.listenedMs ?: 0L
        updatedDaily[day] = DailyListeningEntity(day, current + delta)
        dailyState.value = updatedDaily.values.sortedByDescending { it.day }.take(120)

        val app = context.applicationContext
        scope.launch {
            val db = AudoibooDatabase.get(app)
            val sql = db.openHelper.writableDatabase
            sql.beginTransaction()
            try {
                sql.execSQL("INSERT OR IGNORE INTO daily_listening(day, listenedMs) VALUES(?, 0)", arrayOf(day))
                sql.execSQL("UPDATE daily_listening SET listenedMs = listenedMs + ? WHERE day = ?", arrayOf(delta, day))
                sql.execSQL("INSERT OR IGNORE INTO listening_totals(`key`, listenedMs) VALUES('total', 0)")
                sql.execSQL("UPDATE listening_totals SET listenedMs = listenedMs + ? WHERE `key` = 'total'", arrayOf(delta))
                sql.execSQL("DELETE FROM daily_listening WHERE day NOT IN (SELECT day FROM daily_listening ORDER BY day DESC LIMIT 120)")
                sql.setTransactionSuccessful()
            } finally {
                sql.endTransaction()
            }
            // Refresh the exact persisted total in case initial Room warm-up raced this tick.
            totalState.value = db.libraryDao().listeningTotal()?.listenedMs ?: totalState.value
        }
    }

    private fun bookmarkId(uri: String, position: Long, createdAt: Long): String = "$createdAt|$position|$uri"
}
