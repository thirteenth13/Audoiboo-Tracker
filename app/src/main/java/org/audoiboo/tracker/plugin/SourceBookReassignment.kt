package org.audoiboo.tracker.plugin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Updates one source-book mapping after a canonical book is moved to another known series. */
object SourceBookReassignment {
    suspend fun record(
        context: Context,
        canonicalBookId: String,
        canonicalSeriesId: String,
        book: SourceBook
    ) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        val remoteKey = SourceKeys.remoteKey(book.remoteId, book.url)
        val existing = dao.bookSource(book.sourceId, remoteKey)
            ?: dao.bookSourceByUrl(book.sourceId, book.url)
        val key = existing?.key ?: SourceKeys.bookSourceKey(book.sourceId, remoteKey)
        dao.upsertBookSource(
            BookSourceEntity(
                key = key,
                canonicalBookId = canonicalBookId,
                canonicalSeriesId = canonicalSeriesId,
                sourceId = book.sourceId,
                remoteKey = remoteKey,
                url = book.url,
                remoteTitle = book.title,
                remoteAuthor = book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                remoteOrder = book.seriesNumber,
                confidence = existing?.confidence ?: 1f,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastCheckedAt = now
            )
        )
    }
}
