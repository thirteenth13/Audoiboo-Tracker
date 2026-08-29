package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Moves URI-keyed player state when a library file is copied to another storage provider. */
internal object PlayerUriMigration {
    suspend fun remap(context: Context, oldUri: String, newUri: String) = withContext(Dispatchers.IO) {
        if (oldUri.isBlank() || newUri.isBlank() || oldUri == newUri) return@withContext
        val app = context.applicationContext

        TrackPositionStore.remapUri(app, oldUri, newUri)

        val db = AudoibooDatabase.get(app)
        db.withTransaction {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("UPDATE player_bookmarks SET uri=? WHERE uri=?", arrayOf(newUri, oldUri))
            sql.execSQL("UPDATE playback_resume SET uri=? WHERE uri=?", arrayOf(newUri, oldUri))
            sql.execSQL("UPDATE series_resume SET uri=? WHERE uri=?", arrayOf(newUri, oldUri))
            sql.execSQL(
                "INSERT OR IGNORE INTO broken_tracks(uri, updatedAt) SELECT ?, updatedAt FROM broken_tracks WHERE uri=?",
                arrayOf(newUri, oldUri)
            )
            sql.execSQL("DELETE FROM broken_tracks WHERE uri=?", arrayOf(oldUri))
        }

        PlayerStateStore.refresh(app)
        PlayerExtrasStore.refresh(app)
        PlaybackResumeStore.refresh(app)
    }
}
