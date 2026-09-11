package org.audoiboo.tracker.plugin

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.AudoibooDatabase

data class CanonicalSourceBookLink(
    val canonicalBookId: String,
    val book: SourceBook,
    val confidence: Float = 1f
)

data class PendingBookReview(
    val decision: BookMatchDecisionEntity,
    val source: BookSourceEntity
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

    suspend fun pendingBookReviews(context: Context, canonicalSeriesId: String): List<PendingBookReview> = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        dao.pendingBookMatchDecisions(canonicalSeriesId).mapNotNull { decision ->
            val source = dao.bookSource(decision.sourceId, decision.remoteKey) ?: return@mapNotNull null
            PendingBookReview(decision, source)
        }
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

    suspend fun resolvePendingBookReview(
        context: Context,
        review: PendingBookReview,
        accept: Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val decision = review.decision
        val source = dao.bookSource(decision.sourceId, decision.remoteKey) ?: return@withContext false
        val resolution = PendingBookReviewResolutionPolicy.resolve(
            existingCanonicalBookId = source.canonicalBookId,
            candidateCanonicalBookId = decision.candidateCanonicalBookId,
            accept = accept
        )
        if (!resolution.canResolve) return@withContext false
        val now = System.currentTimeMillis()
        if (accept) {
            dao.upsertBookSource(
                source.copy(
                    canonicalBookId = resolution.canonicalBookId,
                    canonicalSeriesId = decision.canonicalSeriesId,
                    confidence = (decision.confidence ?: source.confidence).coerceIn(0f, 1f),
                    lastSeenAt = now,
                    lastCheckedAt = now
                )
            )
        }
        dao.upsertBookMatchDecision(
            decision.copy(
                decision = resolution.decision,
                decidedAt = now
            )
        )
        true
    }

    suspend fun recordPendingBookReview(
        context: Context,
        canonicalSeriesId: String,
        book: SourceBook,
        candidateCanonicalBookId: String?,
        confidence: Float?
    ) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        val remoteKey = SourceKeys.remoteKey(book.remoteId, book.url)
        val priorDecision = dao.bookMatchDecision(canonicalSeriesId, book.sourceId, remoteKey)
        val keepRejected = priorDecision?.decision == "USER_REJECTED"
        val existing = dao.bookSource(book.sourceId, remoteKey) ?: dao.bookSourceByUrl(book.sourceId, book.url)
        val key = existing?.key ?: SourceKeys.bookSourceKey(book.sourceId, remoteKey)
        dao.upsertBookSource(
            BookSourceEntity(
                key = key,
                canonicalBookId = PendingBookReviewMappingPolicy.canonicalBookId(existing?.canonicalBookId),
                canonicalSeriesId = canonicalSeriesId,
                sourceId = book.sourceId,
                remoteKey = remoteKey,
                url = book.url,
                remoteTitle = book.title,
                remoteAuthor = book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() },
                remoteOrder = book.seriesNumber,
                confidence = confidence?.coerceIn(0f, 1f) ?: existing?.confidence ?: 0f,
                firstSeenAt = existing?.firstSeenAt ?: now,
                lastSeenAt = now,
                lastCheckedAt = now
            )
        )
        if (!keepRejected) {
            dao.upsertBookMatchDecision(
                BookMatchDecisionEntity(
                    canonicalSeriesId = canonicalSeriesId,
                    sourceId = book.sourceId,
                    remoteKey = remoteKey,
                    candidateCanonicalBookId = candidateCanonicalBookId,
                    decision = "REVIEW_PENDING",
                    confidence = confidence?.coerceIn(0f, 1f),
                    decidedAt = now
                )
            )
        }
    }

    suspend fun clearPendingBookReview(context: Context, canonicalSeriesId: String, book: SourceBook) = withContext(Dispatchers.IO) {
        val remoteKey = SourceKeys.remoteKey(book.remoteId, book.url)
        SourceMetadataDatabase.get(context).dao().clearBookMatchDecision(canonicalSeriesId, book.sourceId, remoteKey)
    }

    suspend fun recordSeriesSnapshot(
        context: Context, canonicalSeriesId: String, series: SourceSeries, books: List<CanonicalSourceBookLink>,
        relationship: String = "SAME_SERIES", confidence: Float = 1f, userVerified: Boolean = true
    ) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        val seriesRemoteKey = SourceKeys.remoteKey(series.remoteId, series.url)
        val existingSeries = dao.seriesSource(series.sourceId, seriesRemoteKey) ?: dao.seriesSourceByUrl(series.sourceId, series.url)
        dao.upsertSeriesSource(SeriesSourceEntity(canonicalSeriesId, series.sourceId, seriesRemoteKey, series.url, series.title,
            existingSeries?.relationship ?: relationship, existingSeries?.confidence ?: confidence.coerceIn(0f, 1f),
            SourceMetadataMergePolicy.userVerified(existingSeries?.userVerified, userVerified), existingSeries?.firstSeenAt ?: now, now, now))
        books.forEach { link ->
            val book = link.book
            val remoteKey = SourceKeys.remoteKey(book.remoteId, book.url)
            val existing = dao.bookSource(book.sourceId, remoteKey) ?: dao.bookSourceByUrl(book.sourceId, book.url)
            val key = existing?.key ?: SourceKeys.bookSourceKey(book.sourceId, remoteKey)
            dao.upsertBookSource(BookSourceEntity(key, link.canonicalBookId, canonicalSeriesId, book.sourceId, remoteKey, book.url,
                book.title, book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }, book.seriesNumber,
                existing?.confidence ?: link.confidence.coerceIn(0f, 1f), existing?.firstSeenAt ?: now, now, now))
            dao.clearBookMatchDecision(canonicalSeriesId, book.sourceId, remoteKey)
        }
    }

    suspend fun recordSeriesMatchDecision(context: Context, canonicalSeriesId: String, series: SourceSeries, decision: String, relationship: String, confidence: Float) = withContext(Dispatchers.IO) {
        val remoteKey = SourceKeys.remoteKey(series.remoteId, series.url)
        SourceMetadataDatabase.get(context).dao().upsertMatchDecision(SeriesMatchDecisionEntity(canonicalSeriesId, series.sourceId, remoteKey, decision, relationship, confidence.coerceIn(0f, 1f)))
    }

    suspend fun recordAvailability(context: Context, canonicalBookId: String, sourceId: String, bookUrl: String, candidate: DownloadCandidate) = withContext(Dispatchers.IO) {
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        val remoteKey = SourceKeys.remoteKey(null, bookUrl)
        val existingBook = dao.bookSource(sourceId, remoteKey) ?: dao.bookSourceByUrl(sourceId, bookUrl)
        val key = existingBook?.key ?: SourceKeys.bookSourceKey(sourceId, remoteKey)
        dao.upsertBookSource(BookSourceEntity(key, canonicalBookId, existingBook?.canonicalSeriesId, sourceId, existingBook?.remoteKey ?: remoteKey,
            bookUrl, existingBook?.remoteTitle, existingBook?.remoteAuthor, existingBook?.remoteOrder, existingBook?.confidence ?: 1f,
            existingBook?.firstSeenAt ?: now, now, now))
        val existingAvailability = dao.availability(key).firstOrNull { it.type == candidate.type.name }
        dao.upsertAvailability(SourceAvailabilityEntity(key, sourceId, candidate.type.name, "AVAILABLE", candidate.url,
            existingAvailability?.firstSeenAt ?: now, now, now))
    }

    suspend fun backfillAudiobooMappings(context: Context) = withContext(Dispatchers.IO) {
        val library = AudoibooDatabase.get(context).libraryDao().library()
        val dao = SourceMetadataDatabase.get(context).dao()
        val now = System.currentTimeMillis()
        library.forEach { item ->
            val seriesRemoteKey = SourceKeys.remoteKey(null, item.series.url)
            val existingSeries = dao.seriesSource(AUDIOBOO_SOURCE_ID, seriesRemoteKey) ?: dao.seriesSourceByUrl(AUDIOBOO_SOURCE_ID, item.series.url)
            dao.upsertSeriesSource(SeriesSourceEntity(item.series.id, AUDIOBOO_SOURCE_ID, seriesRemoteKey, item.series.url, item.series.name,
                "SAME_SERIES", 1f, existingSeries?.userVerified ?: true, existingSeries?.firstSeenAt ?: now, now, existingSeries?.lastCheckedAt))
            item.books.forEach { book ->
                val remoteKey = SourceKeys.remoteKey(null, book.url)
                val existingBook = dao.bookSource(AUDIOBOO_SOURCE_ID, remoteKey) ?: dao.bookSourceByUrl(AUDIOBOO_SOURCE_ID, book.url)
                val key = existingBook?.key ?: SourceKeys.bookSourceKey(AUDIOBOO_SOURCE_ID, remoteKey)
                dao.upsertBookSource(BookSourceEntity(key, book.id, item.series.id, AUDIOBOO_SOURCE_ID, remoteKey, book.url, book.title,
                    book.author, null, 1f, existingBook?.firstSeenAt ?: now, now, existingBook?.lastCheckedAt))
                book.archiveUrl?.takeIf { it.isNotBlank() }?.let { archiveUrl ->
                    val existingAvailability = dao.availability(key).firstOrNull { it.type == DownloadType.ARCHIVE.name }
                    dao.upsertAvailability(SourceAvailabilityEntity(key, AUDIOBOO_SOURCE_ID, DownloadType.ARCHIVE.name, "AVAILABLE", archiveUrl,
                        existingAvailability?.firstSeenAt ?: now, now, now))
                }
            }
        }
    }

    fun observeSourcesForBook(context: Context, canonicalBookId: String): Flow<List<BookSourceEntity>> =
        SourceMetadataDatabase.get(context.applicationContext).dao().observeBookSources(canonicalBookId)

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
