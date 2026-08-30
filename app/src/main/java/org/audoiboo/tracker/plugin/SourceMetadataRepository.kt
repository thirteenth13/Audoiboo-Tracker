package org.audoiboo.tracker.plugin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.AudoibooDatabase

data class CanonicalSourceBookLink(
    val canonicalBookId: String,
    val book: SourceBook,
    val confidence: Float = 1f
)

object SourceMetadataRepository {
    const val AUDIOBOO_SOURCE_ID = "audioboo"

    suspend fun registerBuiltInPlugins(context: Context, plugins: Collection<SourcePlugin>) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        plugins.forEach { dao.registerPlugin(it.descriptor) }
    }

    suspend fun canonicalSeriesIdForSource(context: Context, series: SourceSeries): String? = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val remoteKey = SourceKeys.remoteKey(series.remoteId, series.url)
        dao.seriesSource(series.sourceId, remoteKey)?.canonicalSeriesId
            ?: dao.seriesSourceByUrl(series.sourceId, series.url)?.canonicalSeriesId
    }

    suspend fun canonicalBookIdForSource(context: Context, book: SourceBook): String? = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val remoteKey = SourceKeys.remoteKey(book.remoteId, book.url)
        dao.bookSource(book.sourceId, remoteKey)?.canonicalBookId
            ?: dao.bookSourceByUrl(book.sourceId, book.url)?.canonicalBookId
    }

    suspend fun seriesMatchDecisions(context: Context, series: SourceSeries): List<SeriesMatchDecisionEntity> = withContext(Dispatchers.IO) {
        val remoteKey = SourceKeys.remoteKey(series.remoteId, series.url)
        SourceMetadataDatabase.get(context).dao().matchDecisions(series.sourceId, remoteKey)
    }

    suspend fun pendingSeriesReviews(context: Context, canonicalSeriesId: String): List<SeriesMatchDecisionEntity> = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().pendingMatchDecisions(canonicalSeriesId)
    }

    suspend fun resolvePendingSeriesReview(
        context: Context,
        canonicalSeriesId: String,
        sourceId: String,
        remoteKey: String,
        accept: Boolean,
        confidence: Float?
    ) = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().upsertMatchDecision(
            SeriesMatchDecisionEntity(
                canonicalSeriesId = canonicalSeriesId,
                sourceId = sourceId,
                remoteKey = remoteKey,
                decision = if (accept) "USER_ACCEPTED" else "USER_REJECTED",
                relationship = "SAME_SERIES",
                confidence = confidence?.coerceIn(0f, 1f)
            )
        )
    }

    suspend fun recordSeriesSnapshot(
        context: Context,
        canonicalSeriesId: String,
        series: SourceSeries,
        books: List<CanonicalSourceBookLink>,
        relationship: String = "SAME_SERIES",
        confidence: Float = 1f,
        userVerified: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        val seriesRemoteKey = SourceKeys.remoteKey(series.remoteId, series.url)
        val existingSeries = dao.seriesSource(series.sourceId, seriesRemoteKey)
            ?: dao.seriesSourceByUrl(series.sourceId, series.url)
        dao.upsertSeriesSource(
            SeriesSourceEntity(
                canonicalSeriesId = canonicalSeriesId,
                sourceId = series.sourceId,
                remoteKey = seriesRemoteKey,
                url = series.url,
                remoteTitle = series.title,
                relationship = existingSeries?.relationship ?: relationship,
                confidence = existingSeries?.confidence ?: confidence.coerceIn(0f, 1f),
                userVerified = SourceMetadataMergePolicy.userVerified(existingSeries?.userVerified, userVerified),
                firstSeenAt = existingSeries?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastCheckedAt = now
            )
        )

        books.forEach { link ->
            val book = link.book
            val remoteKey = SourceKeys.remoteKey(book.remoteId, book.url)
            val existing = dao.bookSource(book.sourceId, remoteKey)
                ?: dao.bookSourceByUrl(book.sourceId, book.url)
            val key = existing?.key ?: SourceKeys.bookSourceKey(book.sourceId, remoteKey)
            dao.upsertBookSource(
                BookSourceEntity(
                    key = key,
                    canonicalBookId = link.canonicalBookId,
                    canonicalSeriesId = canonicalSeriesId,
                    sourceId = book.sourceId,
                    remoteKey = remoteKey,
                    url = book.url,
                    remoteTitle = book.title,
                    remoteAuthor = book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                    remoteOrder = book.seriesNumber,
                    confidence = existing?.confidence ?: link.confidence.coerceIn(0f, 1f),
                    firstSeenAt = existing?.firstSeenAt ?: now,
                    lastSeenAt = now,
                    lastCheckedAt = now
                )
            )
        }
    }

    suspend fun recordSeriesMatchDecision(
        context: Context,
        canonicalSeriesId: String,
        series: SourceSeries,
        decision: String,
        relationship: String,
        confidence: Float
    ) = withContext(Dispatchers.IO) {
        val remoteKey = SourceKeys.remoteKey(series.remoteId, series.url)
        SourceMetadataDatabase.get(context).dao().upsertMatchDecision(
            SeriesMatchDecisionEntity(
                canonicalSeriesId = canonicalSeriesId,
                sourceId = series.sourceId,
                remoteKey = remoteKey,
                decision = decision,
                relationship = relationship,
                confidence = confidence.coerceIn(0f, 1f)
            )
        )
    }

    suspend fun recordAvailability(
        context: Context,
        canonicalBookId: String,
        sourceId: String,
        bookUrl: String,
        candidate: DownloadCandidate
    ) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        val remoteKey = SourceKeys.remoteKey(null, bookUrl)
        val existingBook = dao.bookSource(sourceId, remoteKey)
            ?: dao.bookSourceByUrl(sourceId, bookUrl)
        val key = existingBook?.key ?: SourceKeys.bookSourceKey(sourceId, remoteKey)
        dao.upsertBookSource(
            BookSourceEntity(
                key = key,
                canonicalBookId = canonicalBookId,
                canonicalSeriesId = existingBook?.canonicalSeriesId,
                sourceId = sourceId,
                remoteKey = existingBook?.remoteKey ?: remoteKey,
                url = bookUrl,
                remoteTitle = existingBook?.remoteTitle,
                remoteAuthor = existingBook?.remoteAuthor,
                remoteOrder = existingBook?.remoteOrder,
                confidence = existingBook?.confidence ?: 1f,
                firstSeenAt = existingBook?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastCheckedAt = now
            )
        )
        val existingAvailability = dao.availability(key).firstOrNull { it.type == candidate.type.name }
        dao.upsertAvailability(
            SourceAvailabilityEntity(
                bookSourceKey = key,
                sourceId = sourceId,
                type = candidate.type.name,
                status = "AVAILABLE",
                uri = candidate.url,
                firstSeenAt = existingAvailability?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastCheckedAt = now
            )
        )
    }

    suspend fun backfillAudiobooMappings(context: Context) = withContext(Dispatchers.IO) {
        val library = AudoibooDatabase.get(context).libraryDao().library()
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()

        library.forEach { item ->
            val seriesRemoteKey = SourceKeys.remoteKey(null, item.series.url)
            val existingSeries = dao.seriesSource(AUDIOBOO_SOURCE_ID, seriesRemoteKey)
                ?: dao.seriesSourceByUrl(AUDIOBOO_SOURCE_ID, item.series.url)
            dao.upsertSeriesSource(
                SeriesSourceEntity(
                    canonicalSeriesId = item.series.id,
                    sourceId = AUDIOBOO_SOURCE_ID,
                    remoteKey = seriesRemoteKey,
                    url = item.series.url,
                    remoteTitle = item.series.name,
                    relationship = "SAME_SERIES",
                    confidence = 1f,
                    userVerified = existingSeries?.userVerified ?: true,
                    firstSeenAt = existingSeries?.firstSeenAt ?: now,
                    lastSeenAt = now,
                    lastCheckedAt = existingSeries?.lastCheckedAt
                )
            )

            item.books.forEach { book ->
                val remoteKey = SourceKeys.remoteKey(null, book.url)
                val existingBook = dao.bookSource(AUDIOBOO_SOURCE_ID, remoteKey)
                    ?: dao.bookSourceByUrl(AUDIOBOO_SOURCE_ID, book.url)
                val key = existingBook?.key ?: SourceKeys.bookSourceKey(AUDIOBOO_SOURCE_ID, remoteKey)
                dao.upsertBookSource(
                    BookSourceEntity(
                        key = key,
                        canonicalBookId = book.id,
                        canonicalSeriesId = item.series.id,
                        sourceId = AUDIOBOO_SOURCE_ID,
                        remoteKey = remoteKey,
                        url = book.url,
                        remoteTitle = book.title,
                        remoteAuthor = book.author,
                        confidence = 1f,
                        firstSeenAt = existingBook?.firstSeenAt ?: now,
                        lastSeenAt = now,
                        lastCheckedAt = existingBook?.lastCheckedAt
                    )
                )

                book.archiveUrl?.takeIf { it.isNotBlank() }?.let { archiveUrl ->
                    val existingAvailability = dao.availability(key).firstOrNull { it.type == DownloadType.ARCHIVE.name }
                    dao.upsertAvailability(
                        SourceAvailabilityEntity(
                            bookSourceKey = key,
                            sourceId = AUDIOBOO_SOURCE_ID,
                            type = DownloadType.ARCHIVE.name,
                            status = "AVAILABLE",
                            uri = archiveUrl,
                            firstSeenAt = existingAvailability?.firstSeenAt ?: now,
                            lastSeenAt = now,
                            lastCheckedAt = now
                        )
                    )
                }
            }
        }
    }

    suspend fun sourcesForBook(context: Context, canonicalBookId: String): List<BookSourceEntity> = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().bookSources(canonicalBookId)
    }

    suspend fun sourcesForSeries(context: Context, canonicalSeriesId: String): List<SeriesSourceEntity> = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().seriesSources(canonicalSeriesId)
    }

    suspend fun availabilityFor(context: Context, bookSourceKey: String): List<SourceAvailabilityEntity> = withContext(Dispatchers.IO) {
        SourceMetadataDatabase.get(context).dao().availability(bookSourceKey)
    }
}
