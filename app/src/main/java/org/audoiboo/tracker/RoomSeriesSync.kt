package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.CanonicalBookMatchInput
import org.audoiboo.tracker.plugin.CanonicalSeriesMatchInput
import org.audoiboo.tracker.plugin.CanonicalSourceBookLink
import org.audoiboo.tracker.plugin.MatchDisposition
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SeriesProvider
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceCapability
import org.audoiboo.tracker.plugin.SourceIdentityMatcher
import org.audoiboo.tracker.plugin.SourceKeys
import org.audoiboo.tracker.plugin.SourceMetadataRepository
import java.util.UUID

internal data class RoomSeriesSyncResult(val seriesId: String, val name: String, val books: Int)

/**
 * Room-native add/update path routed through the active source plugin registry.
 * High-confidence cross-source matches reuse canonical series/books instead of creating duplicates.
 */
internal object RoomSeriesSync {
    suspend fun sync(context: Context, inputUrl: String): RoomSeriesSyncResult? = withContext(Dispatchers.IO) {
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

        val mappedSeriesId = SourceMetadataRepository.canonicalSeriesIdForSource(context, resolved)
        val mappedSeries = mappedSeriesId?.let { id -> library.firstOrNull { it.series.id == id } }
        val directSeries = library.firstOrNull {
            SourceKeys.normalizeUrl(it.series.url) == SourceKeys.normalizeUrl(resolved.url)
        }

        val seriesMatch = if (mappedSeries == null && directSeries == null) {
            SourceIdentityMatcher.bestSeriesMatch(
                incoming = resolved,
                incomingBooks = sourceBooks,
                candidates = library.map(::canonicalSeriesInput)
            )
        } else null
        val autoMatchedSeries = seriesMatch
            ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
            ?.value
            ?.id
            ?.let { id -> library.firstOrNull { it.series.id == id } }

        val selected = mappedSeries ?: directSeries ?: autoMatchedSeries
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
        SourceMetadataRepository.recordSeriesSnapshot(
            context = context,
            canonicalSeriesId = result.seriesId,
            series = resolved,
            books = links,
            relationship = "SAME_SERIES",
            confidence = autoSeriesConfidence ?: 1f,
            userVerified = autoSeriesConfidence == null
        )
        if (autoSeriesConfidence != null) {
            SourceMetadataRepository.recordSeriesMatchDecision(
                context = context,
                canonicalSeriesId = result.seriesId,
                series = resolved,
                decision = "AUTO_ACCEPTED",
                relationship = "SAME_SERIES",
                confidence = autoSeriesConfidence
            )
        }

        LibraryRepository.mirrorLegacy(context)
        RoomCoverSync.enqueueAll(context)
        result
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
