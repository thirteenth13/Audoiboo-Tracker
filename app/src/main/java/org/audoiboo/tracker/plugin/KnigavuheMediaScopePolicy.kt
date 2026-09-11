package org.audoiboo.tracker.plugin

import java.net.URI

/**
 * Knigavuhe can render several books from one cycle on the same page/player.
 * A download result containing media from more than one /audio/<bookId>/ scope must never be
 * treated as one complete canonical book.
 */
internal object KnigavuheMediaScopePolicy {
    data class Result(
        val mediaUrls: List<String>,
        val bookIds: Set<String>,
        val mixedBooks: Boolean
    )

    fun sanitize(mediaUrls: List<String>): Result {
        val distinct = mediaUrls.distinct()
        val ids = distinct.mapNotNull(::bookId).toSet()
        val mixed = ids.size > 1
        return Result(
            mediaUrls = if (mixed) emptyList() else distinct,
            bookIds = ids,
            mixedBooks = mixed
        )
    }

    fun bookId(url: String): String? = runCatching {
        val parts = URI(url).path.orEmpty().split('/').filter(String::isNotBlank)
        val audioIndex = parts.indexOfFirst { it.equals("audio", ignoreCase = true) }
        if (audioIndex < 0 || audioIndex + 1 >= parts.size) return@runCatching null
        parts[audioIndex + 1].takeIf { it.isNotBlank() }
    }.getOrNull()
}
