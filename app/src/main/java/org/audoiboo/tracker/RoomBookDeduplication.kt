package org.audoiboo.tracker

import android.content.Context
import androidx.room.withTransaction
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

/**
 * Conservative logical-book dedupe used for ordinary provider-backed Room series.
 *
 * Older federation code persisted one Room row per provider whenever title/author formatting
 * differed. Examples are "Длань системы. Книга 2" with Лаэндэл / Алексей Андриенко / Алексей
 * Лаэндэл and Audioboo's decorated "Лаэндэл - Длань системы 02". The source rows belong in the
 * metadata database, while Room should contain one canonical row per logical book.
 */
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

        // A decorated provider title can identify a volume that the canonical row represents only
        // by its sort order. Volume 00 deliberately never matches canonical volume 1.
        if (leftVolume != null && leftVolume >= 1 && leftVolume == right.sortIndex + 1 && containsSeries(right.title, seriesTitle)) return true
        if (rightVolume != null && rightVolume >= 1 && rightVolume == left.sortIndex + 1 && containsSeries(left.title, seriesTitle)) return true

        // Prefix/suffix decoration where a useful work title remains after stripping the series.
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
        }.thenBy { SourceIdentityMatcher.normalizeTitle(it.title).length }
            .thenBy { it.sortIndex }
            .thenBy { it.id }
    )

    private fun mergeWinner(winner: BookEntity, group: List<BookEntity>): BookEntity {
        val now = System.currentTimeMillis()
        val preferredAuthor = group
            .mapNotNull { it.author?.takeIf(String::isNotBlank) }
            .maxByOrNull { SourceIdentityMatcher.normalizeAuthor(it).split(' ').size }
        val preferredCover = group.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) }
        val preferredArchive = group.firstNotNullOfOrNull { it.archiveUrl?.takeIf(String::isNotBlank) }
        return winner.copy(
            author = preferredAuthor ?: winner.author,
            coverUrl = preferredCover ?: winner.coverUrl,
            archiveUrl = preferredArchive ?: winner.archiveUrl,
            status = if (group.any { it.status == "READ" }) "READ" else winner.status,
            sortIndex = group.minOf { it.sortIndex },
            updatedAt = now
        )
    }

    private fun containsSeries(title: String, seriesTitle: String): Boolean {
        val titleTokens = SourceIdentityMatcher.normalizeTitle(title).split(' ').filter(String::isNotBlank)
        val seriesTokens = SourceIdentityMatcher.normalizeTitle(seriesTitle).split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size < seriesTokens.size) return false
        return (0..titleTokens.size - seriesTokens.size).any { index ->
            titleTokens.subList(index, index + seriesTokens.size) == seriesTokens
        }
    }

    private fun explicitSeriesVolume(title: String, seriesTitle: String): Int? {
        val titleTokens = SourceIdentityMatcher.normalizeTitle(title).split(' ').filter(String::isNotBlank)
        val seriesTokens = SourceIdentityMatcher.normalizeTitle(seriesTitle).split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size <= seriesTokens.size) return null
        val maxStart = minOf(8, titleTokens.size - seriesTokens.size)
        val start = (0..maxStart).firstOrNull { index ->
            titleTokens.subList(index, index + seriesTokens.size) == seriesTokens
        } ?: return null
        var tail = titleTokens.drop(start + seriesTokens.size)
        if (tail.firstOrNull() in volumeLabel) tail = tail.drop(1)
        return tail.firstOrNull()?.let { token -> numericToken.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun logicalTail(title: String, seriesTitle: String): String {
        val titleTokens = SourceIdentityMatcher.normalizeTitle(title).split(' ').filter(String::isNotBlank)
        val seriesTokens = SourceIdentityMatcher.normalizeTitle(seriesTitle).split(' ').filter(String::isNotBlank)
        if (seriesTokens.isEmpty() || titleTokens.size <= seriesTokens.size) return ""
        val maxStart = minOf(8, titleTokens.size - seriesTokens.size)
        val start = (0..maxStart).firstOrNull { index ->
            titleTokens.subList(index, index + seriesTokens.size) == seriesTokens
        } ?: return ""
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
        dao.library()
            .filterNot { it.series.url.startsWith("catalog://", ignoreCase = true) }
            .forEach { item -> repair(context, item) }
    }

    private suspend fun repair(context: Context, item: SeriesWithBooks) {
        val result = RoomBookDeduplicationPolicy.deduplicate(item.series.name, item.books)
        if (result.duplicateToWinner.isEmpty()) return

        // Re-point every provider mapping before deleting duplicate Room rows. Availability rows
        // keep their source key, so downloads remain attached to the surviving canonical book.
        result.duplicateToWinner.forEach { (duplicateId, winnerId) ->
            SourceMetadataRepository.sourcesForBook(context, duplicateId).forEach { source ->
                SourceBookReassignment.record(
                    context = context,
                    canonicalBookId = winnerId,
                    canonicalSeriesId = item.series.id,
                    book = SourceBook(
                        sourceId = source.sourceId,
                        url = source.url,
                        title = source.remoteTitle.orEmpty(),
                        authors = source.remoteAuthor
                            ?.split(',', ';', '&')
                            .orEmpty()
                            .map(String::trim)
                            .filter(String::isNotBlank)
                            .map(::SourceAuthor),
                        seriesTitle = item.series.name,
                        seriesNumber = source.remoteOrder
                    )
                )
            }
        }

        db.withTransaction {
            dao.upsertBooks(result.books)
            dao.deleteMissingBooks(item.series.id, result.books.map { it.id })
        }
        SeriesDiagnosticLog.i(
            "RoomSeriesSync DEDUPE series=${item.series.name} before=${item.books.size} after=${result.books.size} removed=${result.duplicateToWinner.size}"
        )
    }
}
