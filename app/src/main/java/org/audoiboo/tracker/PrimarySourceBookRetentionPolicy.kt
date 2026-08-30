package org.audoiboo.tracker

/**
 * Keeps books contributed by other sources while allowing a refresh of the canonical
 * source to remove books that disappeared from that source.
 */
internal object PrimarySourceBookRetentionPolicy {
    fun keepIds(
        existingBooks: List<BookEntity>,
        incomingIds: Collection<String>,
        ownedByCurrentSource: (String) -> Boolean
    ): List<String> = buildList {
        addAll(incomingIds)
        existingBooks
            .filterNot { ownedByCurrentSource(it.url) }
            .forEach { add(it.id) }
    }.distinct()
}
