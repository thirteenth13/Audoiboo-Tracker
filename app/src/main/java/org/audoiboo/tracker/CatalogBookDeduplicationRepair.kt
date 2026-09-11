package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
import org.audoiboo.tracker.plugin.AuthorAliasResolver
import org.audoiboo.tracker.plugin.CanonicalBookMatchInput
import org.audoiboo.tracker.plugin.MatchDisposition
import org.audoiboo.tracker.plugin.SeriesDiagnosticLog
import org.audoiboo.tracker.plugin.SourceAuthor
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceBookReassignment
import org.audoiboo.tracker.plugin.SourceIdentityMatcher
import org.audoiboo.tracker.plugin.SourceMetadataRepository

internal object CatalogBookDeduplicationPolicy {
    fun isCanonicalCatalogBook(book: BookEntity): Boolean =
        book.url.startsWith("catalog://", ignoreCase = true) && book.url.contains("/book/", ignoreCase = true)

    fun catalogProviderId(seriesUrl: String): String? {
        if (!seriesUrl.startsWith("catalog://", ignoreCase = true)) return null
        return seriesUrl.substringAfter("catalog://").substringBefore('/').trim().lowercase().takeIf { it.isNotBlank() }
    }

    fun anchors(seriesUrl: String, seriesTitle: String, books: List<BookEntity>): List<BookEntity> {
        val catalogBooks = books.filter(::isCanonicalCatalogBook)
        return if (catalogProviderId(seriesUrl) == "fantlab") {
            RoomBookDeduplicationPolicy.authoritativeFantLabAnchors(seriesTitle, catalogBooks)
        } else {
            catalogBooks
        }
    }
}

internal object CatalogBookDeduplicationRepair {
    suspend fun repairAll(context: Context): Int {
        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        var repaired = 0
        dao.library().filter { it.series.url.startsWith("catalog://", ignoreCase = true) }
            .forEach { repaired += repair(context, db, it) }
        return repaired
    }

    internal suspend fun repair(context: Context, db: AudoibooDatabase, item: SeriesWithBooks): Int {
        if (!item.series.url.startsWith("catalog://", ignoreCase = true)) return 0
        val dao = db.libraryDao()
        val providerId = CatalogBookDeduplicationPolicy.catalogProviderId(item.series.url)
        val anchors = CatalogBookDeduplicationPolicy.anchors(item.series.url, item.series.name, item.books)
        if (anchors.isEmpty()) return 0

        val aliasResolver = AuthorAliasResolver.forContext(context)
        val anchorInputs = anchors.map { anchor ->
            CanonicalBookMatchInput(
                anchor.id,
                anchor.title,
                aliasResolver.expandForMatching(splitAuthors(anchor.author)),
                (anchor.sortIndex + 1).toDouble()
            )
        }
        val anchorIds = anchors.map { it.id }.toSet()
        val duplicateToWinner = linkedMapOf<String, String>()

        item.books.filterNot(CatalogBookDeduplicationPolicy::isCanonicalCatalogBook).forEach { candidate ->
            val candidateAuthors = aliasResolver.expandForMatching(splitAuthors(candidate.author))
            val sourceId = SourceMetadataRepository.sourcesForBook(context, candidate.id).firstOrNull()?.sourceId ?: "room"
            val match = SourceIdentityMatcher.bestBookMatch(
                incoming = SourceBook(
                    sourceId = sourceId,
                    url = candidate.url,
                    title = candidate.title,
                    authors = candidateAuthors.map(::SourceAuthor),
                    seriesTitle = item.series.name,
                    seriesNumber = (candidate.sortIndex + 1).toDouble(),
                    coverUrl = candidate.coverUrl
                ),
                candidates = anchorInputs
            )?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT } ?: return@forEach
            if (match.value.id in anchorIds) duplicateToWinner[candidate.id] = match.value.id
        }
        if (duplicateToWinner.isEmpty()) return 0

        val finalBooks = item.books.associateBy { it.id }.toMutableMap()
        duplicateToWinner.forEach { (duplicateId, winnerId) ->
            val duplicate = finalBooks[duplicateId] ?: return@forEach
            val winner = finalBooks[winnerId] ?: return@forEach
            SourceMetadataRepository.sourcesForBook(context, duplicateId).forEach { source ->
                SourceBookReassignment.record(
                    context,
                    winnerId,
                    item.series.id,
                    SourceBook(
                        sourceId = source.sourceId,
                        url = source.url,
                        title = source.remoteTitle.orEmpty(),
                        authors = splitAuthors(source.remoteAuthor).map(::SourceAuthor),
                        seriesTitle = item.series.name,
                        seriesNumber = source.remoteOrder
                    )
                )
            }
            finalBooks[winnerId] = winner.copy(
                author = winner.author ?: duplicate.author,
                coverUrl = winner.coverUrl ?: duplicate.coverUrl,
                archiveUrl = winner.archiveUrl ?: duplicate.archiveUrl,
                status = if (winner.status == "READ" || duplicate.status == "READ") "READ" else winner.status,
                updatedAt = System.currentTimeMillis()
            )
            finalBooks.remove(duplicateId)
        }

        val winners = finalBooks.values.sortedWith(compareBy<BookEntity> { it.sortIndex }.thenBy { it.title })
        db.withTransaction {
            dao.upsertBooks(winners)
            dao.deleteMissingBooks(item.series.id, winners.map { it.id })
        }
        SeriesDiagnosticLog.i("CatalogBookDeduplicationRepair series=${item.series.name} before=${item.books.size} after=${winners.size} merged=${duplicateToWinner.size} provider=$providerId anchors=${anchors.size}")
        return duplicateToWinner.size
    }

    private fun splitAuthors(value: String?): List<String> = value
        ?.split(',', ';', '&')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        .orEmpty()
}
