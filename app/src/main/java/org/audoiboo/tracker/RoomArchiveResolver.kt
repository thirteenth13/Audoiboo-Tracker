package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.DownloadResolutionPlanner
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SourceAuthor
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceKeys
import org.audoiboo.tracker.plugin.SourceMetadataRepository

internal object RoomArchiveResolver {
    suspend fun resolveAll(context: Context, book: BookEntity): List<String> = withContext(Dispatchers.IO) {
        PluginPackageRuntime.initialize(context.filesDir)

        val primaryPlugin = PluginPackageRuntime.registry.forUrl(book.url)
        val primary = primaryPlugin?.let { plugin ->
            SourceBook(
                sourceId = plugin.descriptor.id,
                url = book.url,
                title = book.title,
                authors = book.author?.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }.orEmpty(),
                coverUrl = book.coverUrl
            )
        }
        val mapped = SourceMetadataRepository.sourcesForBook(context, book.id).map { source ->
            SourceBook(
                sourceId = source.sourceId,
                remoteId = source.remoteKey.takeIf { it != SourceKeys.normalizeUrl(source.url) },
                url = source.url,
                title = source.remoteTitle ?: book.title,
                authors = source.remoteAuthor?.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }
                    ?: book.author?.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }.orEmpty(),
                seriesNumber = source.remoteOrder,
                coverUrl = book.coverUrl
            )
        }
        val sources = buildList {
            if (primary != null) add(primary)
            addAll(mapped)
        }
        val resolved = DownloadResolutionPlanner(PluginPackageRuntime.registry).resolveAll(sources)
        if (resolved.isEmpty()) return@withContext emptyList()

        // Keep the legacy single archive URL only when the source really resolves to one payload.
        // Multi-track books must be re-resolved as a set; persisting only the first MP3 would make
        // the next download silently lose every remaining track.
        if (resolved.size == 1) {
            LibraryRepository.updateBookArchive(context, book.id, resolved.first().candidate.url)
        }
        resolved.forEach { item ->
            SourceMetadataRepository.recordAvailability(
                context = context,
                canonicalBookId = book.id,
                sourceId = item.book.sourceId,
                bookUrl = item.book.url,
                candidate = item.candidate
            )
        }
        resolved.map { it.candidate.url }.distinct()
    }

    suspend fun resolve(context: Context, book: BookEntity): String? =
        resolveAll(context, book).firstOrNull()
}
