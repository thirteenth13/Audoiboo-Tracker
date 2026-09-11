package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
import org.audoiboo.tracker.plugin.AuthorAliasResolver
import org.audoiboo.tracker.plugin.CanonicalBookMatchInput
import org.audoiboo.tracker.plugin.MatchDisposition
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SeriesDiagnosticLog
import org.audoiboo.tracker.plugin.SourceAuthor
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceBookReassignment
import org.audoiboo.tracker.plugin.SourceIdentityMatcher
import org.audoiboo.tracker.plugin.SourceMetadataRepository

internal data class RoomBookDeduplicationResult(
    val books: List<BookEntity>,
    val duplicateToWinner: Map<String, String>
)

/** Conservative logical-book dedupe used for ordinary provider-backed Room series. */
internal object RoomBookDeduplicationPolicy {
    private val volumeLabel = setOf("книга", "кн", "том", "часть", "частина", "book", "volume", "vol")
    private val numericToken = Regex("^#?0*([0-9]{1,3})$")

    fun deduplicate(seriesTitle: String, books: List<BookEntity>): RoomBookDeduplicationResult {
        if (books.size < 2) return RoomBookDeduplicationResult(books, emptyMap())
        val groups = mutableListOf<MutableList<BookEntity>>()
        books.sortedWith(compareBy<BookEntity> { it.sortIndex }.thenBy { it.title }).forEach { book ->
            val group = groups.firstOrNull { existing -> existing.any { sameLogicalBook(seriesTitle, it, book) } }
            if (group == null) groups += mutableListOf(book) else group += book
        }
        val duplicateToWinner = linkedMapOf<String, String>()
        val merged = groups.map { group ->
            if (group.size == 1) return@map group.single()
            val winner = chooseWinner(seriesTitle, group)
            group.filterNot { it.id == winner.id }.forEach { duplicateToWinner[it.id] = winner.id }
            mergeWinner(winner, group)
        }.sortedWith(compareBy<BookEntity> { it.sortIndex }.thenBy { it.title })
        return RoomBookDeduplicationResult(merged, duplicateToWinner)
    }

    internal fun isExplicitPrimaryExtra(seriesTitle: String, title: String): Boolean =
        explicitSeriesVolume(title, seriesTitle) == 0

    /**
     * FantLab cycle payloads may contain a nested subcycle flattened into the parent list. When the
     * parent has a strong contiguous numbered backbone, treat only that backbone (plus the exact
     * unnumbered volume-one title and explicit volume zero) as authoritative anchors. This keeps a
     * nested cycle node and its unnumbered children from becoming permanent books of the parent.
     */
    internal fun authoritativeFantLabAnchors(seriesTitle: String, books: List<BookEntity>): List<BookEntity> {
        if (books.size < 6) return books
        val numbered = books.mapNotNull { explicitSeriesVolume(it.title, seriesTitle) }
            .filter { it >= 1 }
            .distinct()
            .sorted()
        if (numbered.size < 5) return books
        val contiguous = numbered.zipWithNext().all { (left, right) -> right - left <= 1 }
        if (!contiguous) return books

        val normalizedSeries = SourceIdentityMatcher.normalizeTitle(seriesTitle)
        return books.filter { book ->
            val normalizedTitle = SourceIdentityMatcher.normalizeTitle(book.title)
            val volume = explicitSeriesVolume(book.title, seriesTitle)
            normalizedTitle == normalizedSeries || volume != null
        }
    }

    private fun sameLogicalBook(seriesTitle: String, left: BookEntity, right: BookEntity): Boolean {
        val leftTitle = SourceIdentityMatcher.normalizeTitle(left.title)
        val rightTitle = SourceIdentityMatcher.normalizeTitle(right.title)
        if (leftTitle.isBlank() || rightTitle.isBlank()) return false
        val leftAuthor = left.author?.takeIf { it.isNotBlank() }
        val rightAuthor = right.author?.takeIf { it.isNotBlank() }
        val authorsCompatible = when {
            leftAuthor == null || rightAuthor == null -> true
            else -> SourceIdentityMatcher.authorsCompatible(listOf(leftAuthor), listOf(rightAuthor))
        }
        if (!authorsCompatible) return false
        if (leftTitle == rightTitle) return true
        val leftVolume = explicitSeriesVolume(left.title, seriesTitle)
        val rightVolume = explicitSeriesVolume(right.title, seriesTitle)
        if (leftVolume != null && rightVolume != null) return leftVolume == rightVolume
        if (leftVolume != null && leftVolume >= 1 && leftVolume == right.sortIndex + 1 && containsSeries(right.title, seriesTitle)) return true
        if (rightVolume != null && rightVolume >= 1 && rightVolume == left.sortIndex + 1 && containsSeries(left.title, seriesTitle)) return true
        val leftKey = logicalTail(left.title, seriesTitle)
        val rightKey = logicalTail(right.title, seriesTitle)
        return leftKey.isNotBlank() && rightKey.isNotBlank() && leftKey == rightKey
    }

    private fun chooseWinner(seriesTitle: String, group: List<BookEntity>): BookEntity = group.minWith(
        compareBy<BookEntity> { book ->
            val title = SourceIdentityMatcher.normalizeTitle(book.title)
            val series = SourceIdentityMatcher.normalizeTitle(seriesTitle)
            when {
                title == series -> 0
                explicitSeriesVolume(book.title, seriesTitle) == null -> 1
                else -> 2
            }
        }.thenBy { SourceIdentityMatcher.normalizeTitle(it.title).length }.thenBy { it.sortIndex }.thenBy { it.id }
    )

    private fun mergeWinner(winner: BookEntity, group: List<BookEntity>): BookEntity {
        val preferredAuthor = group.mapNotNull { it.author?.takeIf(String::isNotBlank) }
            .maxByOrNull { SourceIdentityMatcher.normalizeAuthor(it).split(' ').size }
        val preferredCover = group.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) }
        val preferredArchive = group.firstNotNullOfOrNull { it.archiveUrl?.takeIf(String::isNotBlank) }
        return winner.copy(
            author = preferredAuthor ?: winner.author,
            coverUrl = preferredCover ?: winner.coverUrl,
            archiveUrl = preferredArchive ?: winner.archiveUrl,
            status = if (group.any { it.status == "READ" }) "READ" else winner.status,
            sortIndex = group.minOf { it.sortIndex },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun containsSeries(title: String, seriesTitle: String): Boolean {
        val titleTokens = SourceIdentityMatcher.normalizeTitle(title).split(' ').filter(String::isNotBlank)
        val seriesTokens = SourceIdentityMatcher.normalizeTitle(seriesTitle).split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size < seriesTokens.size) return false
        return (0..titleTokens.size - seriesTokens.size).any { index -> titleTokens.subList(index, index + seriesTokens.size) == seriesTokens }
    }

    private fun explicitSeriesVolume(title: String, seriesTitle: String): Int? {
        val titleTokens = SourceIdentityMatcher.normalizeTitle(title).split(' ').filter(String::isNotBlank)
        val seriesTokens = SourceIdentityMatcher.normalizeTitle(seriesTitle).split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size <= seriesTokens.size) return null
        val maxStart = minOf(8, titleTokens.size - seriesTokens.size)
        val start = (0..maxStart).firstOrNull { index -> titleTokens.subList(index, index + seriesTokens.size) == seriesTokens } ?: return null
        var tail = titleTokens.drop(start + seriesTokens.size)
        if (tail.firstOrNull() in volumeLabel) tail = tail.drop(1)
        return tail.firstOrNull()?.let { token -> numericToken.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun logicalTail(title: String, seriesTitle: String): String {
        val titleTokens = SourceIdentityMatcher.normalizeTitle(title).split(' ').filter(String::isNotBlank)
        val seriesTokens = SourceIdentityMatcher.normalizeTitle(seriesTitle).split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size <= seriesTokens.size) return ""
        val maxStart = minOf(8, titleTokens.size - seriesTokens.size)
        val start = (0..maxStart).firstOrNull { index -> titleTokens.subList(index, index + seriesTokens.size) == seriesTokens } ?: return ""
        var tail = titleTokens.drop(start + seriesTokens.size)
        if (tail.firstOrNull() in volumeLabel) tail = tail.drop(1)
        if (tail.firstOrNull()?.matches(numericToken) == true) tail = tail.drop(1)
        return tail.joinToString(" ")
    }
}

internal object RoomBookDeduplication {
    suspend fun repairAll(context: Context) {
        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        dao.library().filterNot { it.series.url.startsWith("catalog://", ignoreCase = true) }
            .forEach { item -> repair(context, db, item) }
    }

    internal suspend fun repair(context: Context, db: AudoibooDatabase, item: SeriesWithBooks) {
        val dao = db.libraryDao()
        val base = RoomBookDeduplicationPolicy.deduplicate(item.series.name, item.books)
        val originalSources = item.books.associate { book -> book.id to SourceMetadataRepository.sourcesForBook(context, book.id) }
        val idsByWinner = linkedMapOf<String, MutableSet<String>>()
        base.books.forEach { idsByWinner.getOrPut(it.id) { linkedSetOf() } += it.id }
        base.duplicateToWinner.forEach { (duplicateId, winnerId) -> idsByWinner.getOrPut(winnerId) { linkedSetOf() } += duplicateId }
        val sourcesByWinner = idsByWinner.mapValues { (_, ids) -> ids.flatMap { originalSources[it].orEmpty() } }
        val primarySourceId = PluginPackageRuntime.registry.forUrl(item.series.url)?.descriptor?.id
        val fantlabMappedBooks = base.books.filter { book -> sourcesByWinner[book.id].orEmpty().any { it.sourceId == "fantlab" } }
        val fantlabAnchors = RoomBookDeduplicationPolicy.authoritativeFantLabAnchors(item.series.name, fantlabMappedBooks)

        val extraToWinner = linkedMapOf<String, String>()
        val pruneIds = linkedSetOf<String>()
        val finalBooks = base.books.associateBy { it.id }.toMutableMap()
        var keptExtras = 0

        if (fantlabAnchors.isNotEmpty()) {
            val aliasResolver = AuthorAliasResolver.forContext(context)
            val expandedAnchorInputs = fantlabAnchors.map { anchor ->
                val rawAuthors = anchor.author?.split(',', ';', '&').orEmpty().map(String::trim).filter(String::isNotBlank)
                CanonicalBookMatchInput(anchor.id, anchor.title, aliasResolver.expandForMatching(rawAuthors), (anchor.sortIndex + 1).toDouble())
            }

            base.books.filterNot { candidate -> fantlabAnchors.any { it.id == candidate.id } }.forEach { candidate ->
                val candidateRawAuthors = candidate.author?.split(',', ';', '&').orEmpty().map(String::trim).filter(String::isNotBlank)
                val candidateExpandedAuthors = aliasResolver.expandForMatching(candidateRawAuthors)
                val match = SourceIdentityMatcher.bestBookMatch(
                    incoming = SourceBook(
                        sourceId = primarySourceId ?: "room", url = candidate.url, title = candidate.title,
                        authors = candidateExpandedAuthors.map(::SourceAuthor), seriesTitle = item.series.name,
                        seriesNumber = (candidate.sortIndex + 1).toDouble(), coverUrl = candidate.coverUrl
                    ), candidates = expandedAnchorInputs
                )?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }

                if (match != null) {
                    val anchor = finalBooks[match.value.id] ?: return@forEach
                    extraToWinner[candidate.id] = anchor.id
                    finalBooks[anchor.id] = anchor.copy(
                        coverUrl = anchor.coverUrl ?: candidate.coverUrl,
                        archiveUrl = anchor.archiveUrl ?: candidate.archiveUrl,
                        status = if (anchor.status == "READ" || candidate.status == "READ") "READ" else anchor.status,
                        updatedAt = System.currentTimeMillis()
                    )
                    finalBooks.remove(candidate.id)
                    return@forEach
                }

                val candidateSources = sourcesByWinner[candidate.id].orEmpty()
                val belongsToPrimary = primarySourceId != null && candidateSources.any { it.sourceId == primarySourceId }
                val preserveExplicitExtra = belongsToPrimary && RoomBookDeduplicationPolicy.isExplicitPrimaryExtra(item.series.name, candidate.title)
                if (preserveExplicitExtra) {
                    keptExtras++
                } else if (candidateSources.isNotEmpty()) {
                    pruneIds += candidate.id
                    finalBooks.remove(candidate.id)
                }
            }
        }

        val duplicateToWinner = linkedMapOf<String, String>().apply { putAll(base.duplicateToWinner); putAll(extraToWinner) }
        if (duplicateToWinner.isEmpty() && pruneIds.isEmpty()) return

        duplicateToWinner.forEach { (duplicateId, winnerId) ->
            SourceMetadataRepository.sourcesForBook(context, duplicateId).forEach { source ->
                SourceBookReassignment.record(
                    context = context, canonicalBookId = winnerId, canonicalSeriesId = item.series.id,
                    book = SourceBook(
                        sourceId = source.sourceId, url = source.url, title = source.remoteTitle.orEmpty(),
                        authors = source.remoteAuthor?.split(',', ';', '&').orEmpty().map(String::trim).filter(String::isNotBlank).map(::SourceAuthor),
                        seriesTitle = item.series.name, seriesNumber = source.remoteOrder
                    )
                )
            }
        }

        val winners = finalBooks.values.sortedWith(compareBy<BookEntity> { it.sortIndex }.thenBy { it.title })
        db.withTransaction { dao.upsertBooks(winners); dao.deleteMissingBooks(item.series.id, winners.map { it.id }) }
        SeriesDiagnosticLog.i(
            "RoomSeriesSync DEDUPE series=${item.series.name} before=${item.books.size} after=${winners.size} " +
                "merged=${duplicateToWinner.size} pruned=${pruneIds.size} keptExtras=$keptExtras " +
                "fantlabMapped=${fantlabMappedBooks.size} fantlabAnchors=${fantlabAnchors.size} primary=$primarySourceId"
        )
    }
}
