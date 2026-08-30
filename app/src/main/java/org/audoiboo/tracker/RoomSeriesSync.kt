package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.CanonicalBookMatchInput
import org.audoiboo.tracker.plugin.CanonicalSeriesMatchInput
import org.audoiboo.tracker.plugin.CanonicalSourceBookLink
import org.audoiboo.tracker.plugin.MatchDisposition
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SeriesDecisionPolicy
import org.audoiboo.tracker.plugin.SeriesProvider
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceCapability
import org.audoiboo.tracker.plugin.SourceDiscoveryEngine
import org.audoiboo.tracker.plugin.SourceIdentityMatcher
import org.audoiboo.tracker.plugin.SourceKeys
import org.audoiboo.tracker.plugin.SourceMetadataRepository
import java.util.UUID

internal data class RoomSeriesMatchReview(
    val candidateSeriesId: String,
    val candidateName: String,
    val incomingName: String,
    val confidence: Float,
    val evidence: List<String>
)

internal data class RoomSeriesReviewResolution(
    val candidateSeriesId: String,
    val accept: Boolean,
    val confidence: Float
)

internal data class RoomSeriesSyncResult(
    val seriesId: String?,
    val name: String,
    val books: Int,
    val review: RoomSeriesMatchReview? = null
)

/**
 * Room-native add/update path routed through the active source plugin registry.
 * High-confidence cross-source matches reuse canonical series/books instead of creating duplicates.
 * Ambiguous matches are returned to the UI for an explicit accept/reject decision before mutation.
 */
internal object RoomSeriesSync {
    suspend fun sync(
        context: Context,
        inputUrl: String,
        reviewResolution: RoomSeriesReviewResolution? = null
    ): RoomSeriesSyncResult? = withContext(Dispatchers.IO) {
        PluginPackageRuntime.initialize(context.filesDir)
        val plugin = PluginPackageRuntime.registry.forUrl(inputUrl, SourceCapability.SERIES_LOOKUP) ?: return@withContext null
        val provider = plugin as? SeriesProvider ?: return@withContext null
        val resolved = provider.resolveSeries(inputUrl) ?: return@withContext null
        if (resolved.sourceId != plugin.descriptor.id) return@withContext null
        val sourceBooks = provider.loadSeriesBooks(resolved)
            .filter { it.sourceId == plugin.descriptor.id }
            .distinctBy { SourceKeys.normalizeUrl(it.url) }
        if (sourceBooks.isEmpty()) return@withContext null

        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        val library = dao.library()
        val decisions = SourceMetadataRepository.seriesMatchDecisions(context, resolved)

        if (reviewResolution != null) {
            SourceMetadataRepository.recordSeriesMatchDecision(
                context = context,
                canonicalSeriesId = reviewResolution.candidateSeriesId,
                series = resolved,
                decision = if (reviewResolution.accept) "USER_ACCEPTED" else "USER_REJECTED",
                relationship = "SAME_SERIES",
                confidence = reviewResolution.confidence
            )
        }

        val mappedSeriesId = SourceMetadataRepository.canonicalSeriesIdForSource(context, resolved)
        val mappedSeries = mappedSeriesId?.let { id -> library.firstOrNull { it.series.id == id } }
        val directSeries = library.firstOrNull {
            SourceKeys.normalizeUrl(it.series.url) == SourceKeys.normalizeUrl(resolved.url)
        }
        val acceptedDecisionSeries = decisions
            .firstOrNull { it.decision == "USER_ACCEPTED" }
            ?.canonicalSeriesId
            ?.let { id -> library.firstOrNull { it.series.id == id } }
        val forcedAcceptedSeries = reviewResolution
            ?.takeIf { it.accept }
            ?.candidateSeriesId
            ?.let { id -> library.firstOrNull { it.series.id == id } }

        val rejectedSeriesIds = buildSet {
            decisions.filter { it.decision == "USER_REJECTED" }.forEach { add(it.canonicalSeriesId) }
            reviewResolution?.takeIf { !it.accept }?.let { add(it.candidateSeriesId) }
        }

        val seriesMatch = if (mappedSeries == null && directSeries == null && acceptedDecisionSeries == null && forcedAcceptedSeries == null) {
            SourceIdentityMatcher.bestSeriesMatch(
                incoming = resolved,
                incomingBooks = sourceBooks,
                candidates = library
                    .filterNot { it.series.id in rejectedSeriesIds }
                    .map(::canonicalSeriesInput)
            )
        } else null

        if (seriesMatch?.disposition == MatchDisposition.REVIEW) {
            return@withContext RoomSeriesSyncResult(
                seriesId = null,
                name = resolved.title,
                books = sourceBooks.size,
                review = RoomSeriesMatchReview(
                    candidateSeriesId = seriesMatch.value.id,
                    candidateName = seriesMatch.value.title,
                    incomingName = resolved.title,
                    confidence = seriesMatch.confidence,
                    evidence = seriesMatch.evidence
                )
            )
        }

        val autoMatchedSeries = seriesMatch
            ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
            ?.value
            ?.id
            ?.let { id -> library.firstOrNull { it.series.id == id } }

        val selected = mappedSeries ?: directSeries ?: forcedAcceptedSeries ?: acceptedDecisionSeries ?: autoMatchedSeries
        val canonicalSeriesId = selected?.series?.id ?: UUID.randomUUID().toString()
        val existingBooks = selected?.books.orEmpty()
        val existingBookById = existingBooks.associateBy { it.id }
        val existingBookByUrl = existingBooks.associateBy { SourceKeys.normalizeUrl(it.url) }
        val mappedBookIds = sourceBooks.associateWith { source ->
            SourceMetadataRepository.canonicalBookIdForSource(context, source)
        }

        val usedCanonicalBookIds = linkedSetOf<String>()
        var nextSortIndex = (existingBooks.maxOfOrNull { it.sortIndex } ?: -1) + 1
        val links = mutableListOf<CanonicalSourceBookLink>()

        val result = db.withTransaction {
            val now = System.currentTimeMillis()
            val seriesEntity = when {
                selected == null -> SeriesEntity(canonicalSeriesId, resolved.title, resolved.url, now)
                directSeries != null -> selected.series.copy(name = resolved.title, url = resolved.url, updatedAt = now)
                else -> selected.series.copy(updatedAt = now)
            }
            dao.upsertSeries(seriesEntity)

            val additions = mutableListOf<BookEntity>()
            sourceBooks.forEachIndexed { sourceIndex, source ->
                val mapped = mappedBookIds[source]?.let(existingBookById::get)
                val direct = existingBookByUrl[SourceKeys.normalizeUrl(source.url)]
                val contentMatch = if (mapped == null && direct == null) {
                    SourceIdentityMatcher.bestBookMatch(
                        incoming = source,
                        candidates = existingBooks
                            .filterNot { it.id in usedCanonicalBookIds }
                            .map(::canonicalBookInput)
                    )?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                } else null
                val contentMatched = contentMatch?.value?.id?.let(existingBookById::get)
                val canonical = mapped ?: direct ?: contentMatched
                val confidence = when {
                    mapped != null || direct != null -> 1f
                    contentMatch != null -> contentMatch.confidence
                    else -> 1f
                }

                val entity = if (canonical != null) {
                    usedCanonicalBookIds += canonical.id
                    val sameCanonicalUrl = SourceKeys.normalizeUrl(canonical.url) == SourceKeys.normalizeUrl(source.url)
                    canonical.copy(
                        title = if (sameCanonicalUrl) source.title else canonical.title,
                        author = sourceAuthor(source) ?: canonical.author,
                        coverUrl = source.coverUrl ?: canonical.coverUrl,
                        sortIndex = if (directSeries != null && sameCanonicalUrl) sourceIndex else canonical.sortIndex,
                        updatedAt = now
                    )
                } else {
                    val newId = "$canonicalSeriesId::${source.url}"
                    usedCanonicalBookIds += newId
                    BookEntity(
                        id = newId,
                        seriesId = canonicalSeriesId,
                        title = source.title,
                        url = source.url,
                        author = sourceAuthor(source),
                        coverUrl = source.coverUrl,
                        status = "NEW",
                        archiveUrl = null,
                        sortIndex = if (selected == null) sourceIndex else nextSortIndex++,
                        updatedAt = now
                    )
                }
                additions += entity
                links += CanonicalSourceBookLink(entity.id, source, confidence)
            }
            dao.upsertBooks(additions)

            val totalBooks = (existingBooks.map { it.id } + additions.map { it.id }).distinct().size
            RoomSeriesSyncResult(canonicalSeriesId, seriesEntity.name, totalBooks)
        }

        val autoSeriesConfidence = seriesMatch
            ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT && autoMatchedSeries != null }
            ?.confidence
        val userAcceptedConfidence = reviewResolution
            ?.takeIf { it.accept && forcedAcceptedSeries != null }
            ?.confidence
            ?: decisions.firstOrNull { it.decision == "USER_ACCEPTED" && it.canonicalSeriesId == canonicalSeriesId }?.confidence

        SourceMetadataRepository.recordSeriesSnapshot(
            context = context,
            canonicalSeriesId = canonicalSeriesId,
            series = resolved,
            books = links,
            relationship = "SAME_SERIES",
            confidence = userAcceptedConfidence ?: autoSeriesConfidence ?: 1f,
            userVerified = userAcceptedConfidence != null || (autoSeriesConfidence == null && selected != null)
        )
        if (autoSeriesConfidence != null) {
            SourceMetadataRepository.recordSeriesMatchDecision(
                context = context,
                canonicalSeriesId = canonicalSeriesId,
                series = resolved,
                decision = "AUTO_ACCEPTED",
                relationship = "SAME_SERIES",
                confidence = autoSeriesConfidence
            )
        }

        // Discovery is best-effort and runs only after the canonical sync has committed. A broken
        // alternate source must never roll back or fail the user's primary series update.
        try {
            val canonicalSnapshot = dao.library().firstOrNull { it.series.id == canonicalSeriesId }
            if (canonicalSnapshot != null) {
                discoverAndPersistAlternates(
                    context = context,
                    canonicalSeriesId = canonicalSeriesId,
                    canonical = canonicalSeriesInput(canonicalSnapshot),
                    excludeSourceId = plugin.descriptor.id
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }

        LibraryRepository.mirrorLegacy(context)
        RoomCoverSync.enqueueAll(context)
        result
    }

    private suspend fun discoverAndPersistAlternates(
        context: Context,
        canonicalSeriesId: String,
        canonical: CanonicalSeriesMatchInput,
        excludeSourceId: String
    ) {
        val findings = SourceDiscoveryEngine(PluginPackageRuntime.registry)
            .discoverSeries(canonical, excludeSourceId)
            .filter { it.disposition == MatchDisposition.AUTO_ACCEPT }

        findings.forEach { finding ->
            val decisions = SourceMetadataRepository.seriesMatchDecisions(context, finding.series)
            if (!SeriesDecisionPolicy.allowsAutomaticLink(canonicalSeriesId, decisions)) return@forEach

            val canonicalBooks = canonical.books
            val usedIds = linkedSetOf<String>()
            val links = finding.books.mapNotNull { sourceBook ->
                val match = SourceIdentityMatcher.bestBookMatch(
                    incoming = sourceBook,
                    candidates = canonicalBooks.filterNot { it.id in usedIds }
                )?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                    ?: return@mapNotNull null
                usedIds += match.value.id
                CanonicalSourceBookLink(match.value.id, sourceBook, match.confidence)
            }
            SourceMetadataRepository.recordSeriesSnapshot(
                context = context,
                canonicalSeriesId = canonicalSeriesId,
                series = finding.series,
                books = links,
                relationship = "SAME_SERIES",
                confidence = finding.confidence,
                userVerified = false
            )
            SourceMetadataRepository.recordSeriesMatchDecision(
                context = context,
                canonicalSeriesId = canonicalSeriesId,
                series = finding.series,
                decision = "AUTO_ACCEPTED",
                relationship = "SAME_SERIES",
                confidence = finding.confidence
            )
        }
    }

    private fun canonicalSeriesInput(item: SeriesWithBooks): CanonicalSeriesMatchInput = CanonicalSeriesMatchInput(
        id = item.series.id,
        title = item.series.name,
        authors = item.books.mapNotNull { it.author }.flatMap(::splitAuthors).distinct(),
        books = item.books.map(::canonicalBookInput)
    )

    private fun canonicalBookInput(book: BookEntity): CanonicalBookMatchInput = CanonicalBookMatchInput(
        id = book.id,
        title = book.title,
        authors = book.author?.let(::splitAuthors).orEmpty(),
        number = (book.sortIndex + 1).toDouble()
    )

    private fun sourceAuthor(book: SourceBook): String? =
        book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }

    private fun splitAuthors(value: String): List<String> = value
        .split(',', ';', '&')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
