package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RoomArchiveResolver {
    suspend fun resolve(context: Context, book: BookEntity): String? = withContext(Dispatchers.IO) {
        val archive = AudiobooFastParser.findArchive(book.url) ?: return@withContext null
        LibraryRepository.updateBookArchive(context, book.id, archive)
        archive
    }
}
