package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Transitional one-way mirror from the existing PlayerExtras API into Room v4. */
internal object PlayerExtrasRoomSync {
    suspend fun syncFromLegacy(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val history = PlayerExtras.history(app).map {
            PlaybackHistoryEntity(dir = it.dir, title = it.title, at = it.at)
        }
        val bookmarks = PlayerExtras.bookmarks(app).map {
            PlayerBookmarkEntity(
                id = bookmarkId(it.uri, it.position, it.createdAt),
                uri = it.uri,
                positionMs = it.position,
                note = it.note,
                createdAt = it.createdAt
            )
        }
        val daily = PlayerExtras.dailyListened(app).entries
            .sortedByDescending { it.key }
            .take(120)
            .map { DailyListeningEntity(day = it.key, listenedMs = it.value.coerceAtLeast(0L)) }
        AudoibooDatabase.get(app).libraryDao().replacePlayerExtras(
            history = history,
            bookmarks = bookmarks,
            daily = daily,
            totalMs = PlayerExtras.totalListenedMs(app)
        )
    }

    private fun bookmarkId(uri: String, position: Long, createdAt: Long): String =
        "$createdAt|$position|$uri"
}
