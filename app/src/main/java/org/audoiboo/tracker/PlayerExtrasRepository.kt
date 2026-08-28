package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

internal data class ListeningStatsSnapshot(
    val totalMs: Long,
    val daily: List<DailyListeningEntity>
)

/** Room-native read API for player extras during the staged migration from SharedPreferences. */
internal object PlayerExtrasRepository {
    fun observeHistory(context: Context): Flow<List<PlaybackHistoryEntity>> =
        AudoibooDatabase.get(context.applicationContext).libraryDao().observePlaybackHistory()

    fun observeBookmarks(context: Context): Flow<List<PlayerBookmarkEntity>> =
        AudoibooDatabase.get(context.applicationContext).libraryDao().observePlayerBookmarks()

    fun observeDaily(context: Context): Flow<List<DailyListeningEntity>> =
        AudoibooDatabase.get(context.applicationContext).libraryDao().observeDailyListening()

    suspend fun history(context: Context): List<PlaybackHistoryEntity> = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context.applicationContext).libraryDao().playbackHistory()
    }

    suspend fun bookmarks(context: Context): List<PlayerBookmarkEntity> = withContext(Dispatchers.IO) {
        AudoibooDatabase.get(context.applicationContext).libraryDao().playerBookmarks()
    }

    suspend fun stats(context: Context): ListeningStatsSnapshot = withContext(Dispatchers.IO) {
        val dao = AudoibooDatabase.get(context.applicationContext).libraryDao()
        ListeningStatsSnapshot(
            totalMs = dao.listeningTotal()?.listenedMs ?: 0L,
            daily = dao.dailyListening()
        )
    }

    /** Ensure a device upgraded from an older build has Room populated before first Room-native read. */
    suspend fun reconcile(context: Context) = PlayerExtrasRoomSync.syncFromLegacy(context.applicationContext)
}
