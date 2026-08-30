package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.audoiboo.tracker.plugin.DownloadResolver
import org.audoiboo.tracker.plugin.DownloadType
import org.audoiboo.tracker.plugin.PluginPackageRuntime
import org.audoiboo.tracker.plugin.SourceAuthor
import org.audoiboo.tracker.plugin.SourceBook
import org.audoiboo.tracker.plugin.SourceMetadataRepository

internal object RoomArchiveResolver {
    suspend fun resolve(context: Context, book: BookEntity): String? = withContext(Dispatchers.IO) {
        PluginPackageRuntime.initialize(context.filesDir)
        val plugin = PluginPackageRuntime.registry.forUrl(book.url) ?: return@withContext null
        val resolver = plugin as? DownloadResolver ?: return@withContext null
        val sourceBook = SourceBook(
            sourceId = plugin.descriptor.id,
            url = book.url,
            title = book.title,
            authors = book.author?.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }.orEmpty(),
            coverUrl = book.coverUrl
        )
        val candidate = resolver.resolveDownloads(sourceBook)
            .filter { it.type == DownloadType.ARCHIVE || it.type == DownloadType.DIRECT_FILE }
            .maxWithOrNull(compareBy<org.audoiboo.tracker.plugin.DownloadCandidate> { it.priority }.thenBy { it.type == DownloadType.ARCHIVE })
            ?: return@withContext null

        LibraryRepository.updateBookArchive(context, book.id, candidate.url)
        SourceMetadataRepository.recordAvailability(
            context = context,
            canonicalBookId = book.id,
            sourceId = plugin.descriptor.id,
            bookUrl = book.url,
            candidate = candidate
        )
        candidate.url
    }
}
