package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.DownloadResolutionPlanner
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SourceAuthor
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceMetadataRepository

internal object RoomArchiveResolver {
    suspend fun resolve(context: Context, book: BookEntity): String? = withContext(Dispatchers.IO) {
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
                remoteId = source.remoteKey.takeIf { it != source.url },
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
        val resolved = DownloadResolutionPlanner(PluginPackageRuntime.registry).resolve(sources)
            ?: return@withContext null

        LibraryRepository.updateBookArchive(context, book.id, resolved.candidate.url)
        SourceMetadataRepository.recordAvailability(
            context = context,
            canonicalBookId = book.id,
            sourceId = resolved.book.sourceId,
            bookUrl = resolved.book.url,
            candidate = resolved.candidate
        )
        resolved.candidate.url
    }
}
