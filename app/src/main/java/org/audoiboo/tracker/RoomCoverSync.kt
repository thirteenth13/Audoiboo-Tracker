package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RoomCoverSync {
    suspend fun enqueueAll(context: Context) = withContext(Dispatchers.IO) {
        LibraryRepository.snapshot(context)
            .asSequence()
            .flatMap { it.books.asSequence() }
            .mapNotNull { it.coverUrl?.takeIf { url -> url.startsWith("http", true) } }
            .distinct()
            .forEach { CoverCache.enqueue(context, it) }
        CoverCache.prune(context)
    }
}
