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
import org.audoiboo.tracker.plugin.SeriesBookMembershipPolicy
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
    private const val DISCOVERY_PREFS = "source_discovery"
    private const val DISCOVERY_LAST_SUCCESS_PREFIX = "last_success:"

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
        val rawSourceBooks = provider.loadSeriesBooks(resolved)
            .filter { it.sourceId == plugin.descriptor.id }
            .distinctBy { SourceKeys.normalizeUrl(it.url) }
        val sourceBooks = SeriesBookMembershipPolicy.filter(resolved, rawSourceBooks)
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
        val allBooksByUrl = library.flatMap { it.books }.associateBy { SourceKeys.normalizeUrl(it.url) }
        val nestedTargets = rawSourceBooks.mapNotNull { source ->
            if (SeriesBookMembershipPolicy.belongsTo(resolved, source)) return@mapNotNull null
            val key = SeriesBookMembershipPolicy.inferredNestedSeriesKey(resolved, source) ?: return@mapNotNull null
            val target = library.firstOrNull {
                it.series.id != canonicalSeriesId && SourceIdentityMatcher.normalizeTitle(it.series.name) == key
            } ?: return@mapNotNull null
            source to target
        }
        val mappedBookIds = sourceBooks.associateWith { source ->
            SourceMetadataRepository.canonicalBookIdForSource(context, source)
        }

        val usedCanonicalBookIds = linkedSetOf<String>()
        var nextSortIndex = (existingBooks.maxOfOrNull { it.sortIndex } ?: -1) + 1
        val links = mutableListOf<CanonicalSourceBookLink>()
        val rehomedLinks = mutableListOf<Pair<String, SourceBook>>()

        val result = db.withTransaction {
            val now = System.currentTimeMillis()
            val seriesEntity = when {
                selected == null -> SeriesEntity(canonicalSeriesId, resolved.title, resolved.url, now)
                directSeries != null -> selected.series.copy(name = resolved.title, url = resolved.url, updatedAt = now)
                else -> selected.series.copy(updatedAt = now)
            }
            dao.upsertSeries(seriesEntity)

            nestedTargets.groupBy { it.second.series.id }.forEach { (_, entries) ->
                val target = entries.first().second
                val targetBooksByUrl = target.books.associateBy { SourceKeys.normalizeUrl(it.url) }

                // Keep an already-known target subseries ordered by the nested volume marker when
                // its title exposes one (for example 01/02/03), instead of leaving a previously
                // lone third book at sort index zero.
                target.books.forEach { existing ->
                    val pseudo = SourceBook(
                        sourceId = plugin.descriptor.id,
                        url = existing.url,
                        title = existing.title,
                        seriesTitle = resolved.title
                    )
                    val number = SeriesBookMembershipPolicy.inferredNestedVolumeNumber(resolved, pseudo)
                    if (number != null && existing.sortIndex != number - 1) {
                        dao.upsertBooks(listOf(existing.copy(sortIndex = (number - 1).coerceAtLeast(0), updatedAt = now)))
                    }
                }

                entries.forEach { (source, _) ->
                    val normalizedUrl = SourceKeys.normalizeUrl(source.url)
                    val directTarget = targetBooksByUrl[normalizedUrl]
                    val anywhere = allBooksByUrl[normalizedUrl]
                    val number = SeriesBookMembershipPolicy.inferredNestedVolumeNumber(resolved, source)
                    val entity = when (val existing = directTarget ?: anywhere) {
                        null -> BookEntity(
                            id = "${target.series.id}::${source.url}",
                            seriesId = target.series.id,
                            title = source.title,
                            url = source.url,
                            author = sourceAuthor(source),
                            coverUrl = source.coverUrl,
                            status = "NEW",
                            archiveUrl = null,
                            sortIndex = number?.minus(1)?.coerceAtLeast(0)
                                ?: ((target.books.maxOfOrNull { it.sortIndex } ?: -1) + 1),
                            updatedAt = now
                        )
                        else -> existing.copy(
                            seriesId = target.series.id,
                            title = source.title,
                            author = sourceAuthor(source) ?: existing.author,
                            coverUrl = source.coverUrl ?: existing.coverUrl,
                            sortIndex = number?.minus(1)?.coerceAtLeast(0) ?: existing.sortIndex,
                            updatedAt = now
                        )
                    }
                    dao.upsertBooks(listOf(entity))
                    rehomedLinks += entity.id to source
                }
            }

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

            val retainedIds = if (directSeries != null) {
                PrimarySourceBookRetentionPolicy.keepIds(
                    existingBooks = existingBooks,
                    incomingIds = additions.map { it.id },
                    ownedByCurrentSource = plugin::supports
                ).also { dao.deleteMissingBooks(canonicalSeriesId, it) }
            } else {
                (existingBooks.map { it.id } + additions.map { it.id }).distinct()
            }
            dao.upsertBooks(additions)

            RoomSeriesSyncResult(canonicalSeriesId, seriesEntity.name, retainedIds.size)
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
        rehomedLinks.forEach { (canonicalBookId, source) ->
            SourceMetadataRepository.recordAvailability(
                context = context,
                canonicalBookId = canonicalBookId,
                sourceId = source.sourceId,
                bookUrl = source.url,
                candidate = org.audoiboo.tracker.plugin.DownloadCandidate(
                    type = org.audoiboo.tracker.plugin.DownloadType.DIRECT_FILE,
                    url = source.url
                )
            )
        }
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

        // Discovery is best-effort and runs only after the canonical sync has committed. Repeated
        // manual refreshes are throttled, while explicit review resolutions force a fresh pass.
        val discoveryPrefs = context.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
        val discoveryKey = "$DISCOVERY_LAST_SUCCESS_PREFIX$canonicalSeriesId"
        val discoveryNow = System.currentTimeMillis()
        if (SourceDiscoveryThrottle.shouldRun(
                lastSuccessAt = discoveryPrefs.getLong(discoveryKey, 0L),
                now = discoveryNow,
                force = reviewResolution != null
            )) {
            try {
                val canonicalSnapshot = dao.seriesWithBooks(canonicalSeriesId)
                if (canonicalSnapshot != null) {
                    discoverAndPersistAlternates(
                        context = context,
                        canonicalSeriesId = canonicalSeriesId,
                        canonical = canonicalSeriesInput(canonicalSnapshot),
                        excludeSourceId = plugin.descriptor.id
                    )
                    discoveryPrefs.edit().putLong(discoveryKey, System.currentTimeMillis()).apply()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
            }
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

        findings.forEach { finding ->
            val decisions = SourceMetadataRepository.seriesMatchDecisions(context, finding.series)
            val acceptedReview = SeriesDecisionPolicy.isUserAccepted(canonicalSeriesId, decisions)

            if (finding.disposition == MatchDisposition.REVIEW && !acceptedReview) {
                if (SeriesDecisionPolicy.shouldQueueReview(canonicalSeriesId, decisions)) {
                    SourceMetadataRepository.recordSeriesMatchDecision(
                        context = context,
                        canonicalSeriesId = canonicalSeriesId,
                        series = finding.series,
                        decision = "REVIEW_PENDING",
                        relationship = "SAME_SERIES",
                        confidence = finding.confidence
                    )
                }
                return@forEach
            }

            if (finding.disposition != MatchDisposition.AUTO_ACCEPT && !acceptedReview) return@forEach
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
                userVerified = acceptedReview
            )
            SourceMetadataRepository.recordSeriesMatchDecision(
                context = context,
                canonicalSeriesId = canonicalSeriesId,
                series = finding.series,
                decision = if (acceptedReview) "USER_ACCEPTED" else "AUTO_ACCEPTED",
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
