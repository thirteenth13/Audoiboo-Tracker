package org.audoiboo.tracker

import org.audoiboo.tracker.plugin.BookSourceEntity
import org.audoiboo.tracker.plugin.SourceKeys

/** Page navigation is observation-level, unlike download selection which is provider-level. */
internal object SourcePageOptionPolicy {
    fun options(sources: List<BookSourceEntity>): List<BookSourceEntity> =
        SourceObservationPreference.rank(sources)
            .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.url) }

    fun providerIds(sources: List<BookSourceEntity>): List<String> =
        options(sources).map { it.sourceId }.distinct()

    fun needsObservationHint(source: BookSourceEntity, all: List<BookSourceEntity>): Boolean =
        options(all).count { it.sourceId == source.sourceId } > 1

    fun observationHint(source: BookSourceEntity): String {
        val title = source.remoteTitle?.trim().orEmpty()
        if (title.isNotBlank()) return title
        return runCatching {
            val uri = java.net.URI(source.url)
            listOfNotNull(uri.host, uri.path?.trim('/')?.takeIf { it.isNotBlank() })
                .joinToString("/")
        }.getOrDefault(source.url)
    }
}
