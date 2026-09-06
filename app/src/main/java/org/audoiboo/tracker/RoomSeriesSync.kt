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
import org.audoiboo.tracker.plugin.SourceBookReassignment
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
 * Canonical series are a union across providers: a partial provider refresh may add/hydrate books,
 * but never removes books learned from the catalog or another provider.
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

        // A provider URL may already have created a standalone series before catalog federation was
        // introduced. Prefer an AUTO_ACCEPT catalog canonical even when that direct series exists,
        // then retire the old duplicate below.
        val catalogMatch = if (mappedSeries == null && forcedAcceptedSeries == null && acceptedDecisionSeries == null) {
            SourceIdentityMatcher.bestSeriesMatch(
                incoming = resolved,
                incomingBooks = sourceBooks,
                candidates = library
                    .filter { it.series.url.startsWith("catalog://", ignoreCase = true) }
                    .filterNot { it.series.id in rejectedSeriesIds }
                    .filterNot { it.series.id == directSeries?.series?.id }
                    .map(::canonicalSeriesInput)
            )
        } else null
        val catalogMatchedSeries = catalogMatch
            ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
            ?.value?.id
            ?.let { id -> library.firstOrNull { it.series.id == id } }

        val seriesMatch = if (mappedSeries == null && directSeries == null && acceptedDecisionSeries == null && forcedAcceptedSeries == null && catalogMatchedSeries == null) {
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
            ?.value?.id
            ?.let { id -> library.firstOrNull { it.series.id == id } }

        val selected = mappedSeries ?: forcedAcceptedSeries ?: acceptedDecisionSeries ?: catalogMatchedSeries ?: autoMatchedSeries ?: directSeries
        val duplicateDirectSeries = directSeries?.takeIf { selected != null && it.series.id != selected.series.id }
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
        val rehomedLinks = mutableListOf<Triple<String, String, SourceBook>>()

        val result = db.withTransaction {
            val now = System.currentTimeMillis()
            val seriesEntity = when {
                selected == null -> SeriesEntity(canonicalSeriesId, resolved.title, resolved.url, now)
                directSeries != null && selected.series.id == directSeries.series.id -> selected.series.copy(name = resolved.title, url = resolved.url, updatedAt = now)
                else -> selected.series.copy(updatedAt = now)
            }
            dao.upsertSeries(seriesEntity)

            nestedTargets.groupBy { it.second.series.id }.forEach { (_, entries) ->
                val target = entries.first().second
                val targetBooksByUrl = target.books.associateBy { SourceKeys.normalizeUrl(it.url) }
                target.books.forEach { existing ->
                    val pseudo = SourceBook(plugin.descriptor.id, existing.url, existing.title, seriesTitle = resolved.title)
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
                            id = "${target.series.id}::${source.url}", seriesId = target.series.id,
                            title = source.title, url = source.url, author = sourceAuthor(source), coverUrl = source.coverUrl,
                            status = "NEW", archiveUrl = null,
                            sortIndex = number?.minus(1)?.coerceAtLeast(0) ?: ((target.books.maxOfOrNull { it.sortIndex } ?: -1) + 1), updatedAt = now
                        )
                        else -> existing.copy(
                            seriesId = target.series.id, title = source.title,
                            author = sourceAuthor(source) ?: existing.author, coverUrl = source.coverUrl ?: existing.coverUrl,
                            sortIndex = number?.minus(1)?.coerceAtLeast(0) ?: existing.sortIndex, updatedAt = now
                        )
                    }
                    dao.upsertBooks(listOf(entity))
                    rehomedLinks += Triple(entity.id, target.series.id, source)
                }
            }

            val additions = mutableListOf<BookEntity>()
            sourceBooks.forEachIndexed { sourceIndex, source ->
                val mapped = mappedBookIds[source]?.let(existingBookById::get)
                val direct = existingBookByUrl[SourceKeys.normalizeUrl(source.url)]
                val contentMatch = if (mapped == null && direct == null) {
                    SourceIdentityMatcher.bestBookMatch(
                        incoming = source,
                        candidates = existingBooks.filterNot { it.id in usedCanonicalBookIds }.map(::canonicalBookInput)
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
                        sortIndex = if (directSeries != null && selected?.series?.id == directSeries.series.id && sameCanonicalUrl) sourceIndex else canonical.sortIndex,
                        updatedAt = now
                    )
                } else {
                    val newId = "$canonicalSeriesId::${source.url}"
                    usedCanonicalBookIds += newId
                    BookEntity(
                        id = newId, seriesId = canonicalSeriesId, title = source.title, url = source.url,
                        author = sourceAuthor(source), coverUrl = source.coverUrl, status = "NEW", archiveUrl = null,
                        sortIndex = if (selected == null) sourceIndex else nextSortIndex++, updatedAt = now
                    )
                }
                additions += entity
                links += CanonicalSourceBookLink(entity.id, source, confidence)
            }

            // Preserve every canonical row that another provider/catalog may know about. A provider
            // returning 6 books after the catalog supplied 10 must leave all 10 intact.
            dao.upsertBooks(additions)

            // Retire a pre-federation standalone duplicate after its current provider snapshot has
            // been linked into the preferred canonical series. Exact duplicate books are already
            // represented by the canonical rows; unmatched legacy books are carried over first.
            duplicateDirectSeries?.let { duplicate ->
                val currentCanonical = (existingBooks + additions).associateBy { it.id }.toMutableMap()
                var carryIndex = (currentCanonical.values.maxOfOrNull { it.sortIndex } ?: -1) + 1
                val carried = duplicate.books.mapNotNull { old ->
                    val match = SourceIdentityMatcher.bestBookMatch(
                        incoming = SourceBook(
                            sourceId = plugin.descriptor.id,
                            url = old.url,
                            title = old.title,
                            authors = old.author?.let(::splitAuthors).orEmpty().map { org.audoiboo.tracker.plugin.SourceAuthor(it) },
                            seriesTitle = resolved.title,
                            seriesNumber = (old.sortIndex + 1).toDouble(),
                            coverUrl = old.coverUrl
                        ),
                        candidates = currentCanonical.values.map(::canonicalBookInput)
                    )?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                    if (match != null) null
                    else old.copy(seriesId = canonicalSeriesId, sortIndex = carryIndex++, updatedAt = now)
                }
                if (carried.isNotEmpty()) dao.upsertBooks(carried)
                dao.deleteSeries(duplicate.series.id)
            }

            val finalCount = dao.seriesWithBooks(canonicalSeriesId)?.books?.size
                ?: (existingBooks.map { it.id } + additions.map { it.id }).distinct().size
            RoomSeriesSyncResult(canonicalSeriesId, seriesEntity.name, finalCount)
        }

        val autoSeriesConfidence = catalogMatch
            ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT && catalogMatchedSeries != null }
            ?.confidence
            ?: seriesMatch?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT && autoMatchedSeries != null }?.confidence
        val userAcceptedConfidence = reviewResolution
            ?.takeIf { it.accept && forcedAcceptedSeries != null }
            ?.confidence
            ?: decisions.firstOrNull { it.decision == "USER_ACCEPTED" && it.canonicalSeriesId == canonicalSeriesId }?.confidence

        SourceMetadataRepository.recordSeriesSnapshot(
            context = context, canonicalSeriesId = canonicalSeriesId, series = resolved, books = links,
            relationship = "SAME_SERIES", confidence = userAcceptedConfidence ?: autoSeriesConfidence ?: 1f,
            userVerified = userAcceptedConfidence != null || (autoSeriesConfidence == null && selected != null)
        )
        rehomedLinks.forEach { (canonicalBookId, targetSeriesId, source) ->
            SourceBookReassignment.record(context, canonicalBookId, targetSeriesId, source)
        }
        if (autoSeriesConfidence != null) {
            SourceMetadataRepository.recordSeriesMatchDecision(
                context, canonicalSeriesId, resolved, "AUTO_ACCEPTED", "SAME_SERIES", autoSeriesConfidence
            )
        }

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
        dao.seriesWithBooks(canonicalSeriesId)?.let { result.copy(books = it.books.size) } ?: result
    }

    private suspend fun discoverAndPersistAlternates(
        context: Context,
        canonicalSeriesId: String,
        canonical: CanonicalSeriesMatchInput,
        excludeSourceId: String
    ) {
        val findings = SourceDiscoveryEngine(PluginPackageRuntime.registry).discoverSeries(canonical, excludeSourceId)
        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()

        findings.forEach { finding ->
            val decisions = SourceMetadataRepository.seriesMatchDecisions(context, finding.series)
            val acceptedReview = SeriesDecisionPolicy.isUserAccepted(canonicalSeriesId, decisions)
            if (finding.disposition == MatchDisposition.REVIEW && !acceptedReview) {
                if (SeriesDecisionPolicy.shouldQueueReview(canonicalSeriesId, decisions)) {
                    SourceMetadataRepository.recordSeriesMatchDecision(
                        context, canonicalSeriesId, finding.series, "REVIEW_PENDING", "SAME_SERIES", finding.confidence
                    )
                }
                return@forEach
            }
            if (finding.disposition != MatchDisposition.AUTO_ACCEPT && !acceptedReview) return@forEach
            if (!SeriesDecisionPolicy.allowsAutomaticLink(canonicalSeriesId, decisions)) return@forEach

            val links = db.withTransaction {
                val snapshot = dao.seriesWithBooks(canonicalSeriesId) ?: return@withTransaction emptyList()
                val canonicalBooks = snapshot.books.sortedBy { it.sortIndex }.toMutableList()
                val usedIds = linkedSetOf<String>()
                var nextSortIndex = (canonicalBooks.maxOfOrNull { it.sortIndex } ?: -1) + 1
                val now = System.currentTimeMillis()
                val sourceLinks = mutableListOf<CanonicalSourceBookLink>()

                SeriesBookMembershipPolicy.filter(finding.series, finding.books).forEach { sourceBook ->
                    val match = SourceIdentityMatcher.bestBookMatch(
                        incoming = sourceBook,
                        candidates = canonicalBooks.filterNot { it.id in usedIds }.map(::canonicalBookInput)
                    )?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                    val existing = match?.value?.id?.let { id -> canonicalBooks.firstOrNull { it.id == id } }
                    val entity = if (existing != null) {
                        usedIds += existing.id
                        val replaceCatalogUrl = existing.url.startsWith("catalog://", ignoreCase = true)
                        existing.copy(
                            url = if (replaceCatalogUrl) sourceBook.url else existing.url,
                            author = sourceAuthor(sourceBook) ?: existing.author,
                            coverUrl = sourceBook.coverUrl ?: existing.coverUrl,
                            updatedAt = now
                        )
                    } else {
                        val numberIndex = sourceBook.seriesNumber?.takeIf { it >= 1.0 }?.toInt()?.minus(1)?.coerceAtLeast(0)
                        val newId = "$canonicalSeriesId::${sourceBook.url}"
                        usedIds += newId
                        BookEntity(
                            id = newId, seriesId = canonicalSeriesId, title = sourceBook.title, url = sourceBook.url,
                            author = sourceAuthor(sourceBook), coverUrl = sourceBook.coverUrl, status = "NEW", archiveUrl = null,
                            sortIndex = numberIndex ?: nextSortIndex++, updatedAt = now
                        )
                    }
                    dao.upsertBooks(listOf(entity))
                    canonicalBooks.removeAll { it.id == entity.id }
                    canonicalBooks += entity
                    sourceLinks += CanonicalSourceBookLink(entity.id, sourceBook, match?.confidence ?: 1f)
                }
                sourceLinks
            }

            SourceMetadataRepository.recordSeriesSnapshot(
                context, canonicalSeriesId, finding.series, links, "SAME_SERIES", finding.confidence, acceptedReview
            )
            SourceMetadataRepository.recordSeriesMatchDecision(
                context, canonicalSeriesId, finding.series,
                if (acceptedReview) "USER_ACCEPTED" else "AUTO_ACCEPTED", "SAME_SERIES", finding.confidence
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
