package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RoomTagSync {
    suspend fun tagsForDir(context: Context, dir: String): List<String> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val tracker = LibraryRepository.snapshot(app)
        val id = resolveBookId(tracker, playerBooks(app), dir) ?: return@withContext emptyList()
        AudoibooDatabase.get(app).libraryDao().bookWithTags(id)?.tags?.map { it.name }.orEmpty()
    }

    suspend fun tagsForDirs(context: Context, dirs: List<String>): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val tracker = LibraryRepository.snapshot(app)
        val player = playerBooks(app)
        val dao = AudoibooDatabase.get(app).libraryDao()
        dirs.distinct().associateWith { dir ->
            val id = resolveBookId(tracker, player, dir)
            if (id == null) emptyList() else dao.bookWithTags(id)?.tags?.map { it.name }.orEmpty()
        }
    }

    suspend fun setTagsForDir(context: Context, dir: String, tags: List<String>): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val tracker = LibraryRepository.snapshot(app)
        val id = resolveBookId(tracker, playerBooks(app), dir) ?: return@withContext false
        val clean = tags.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        AudoibooDatabase.get(app).libraryDao().setBookTags(id, clean)
        PlayerTagStore.setCached(dir, clean)
        true
    }

    private fun playerBooks(context: Context): Map<String, PlayerLibraryItem?> = PlayerLibrary.all(context)
        .groupBy { normalizeDir(it.relativePath) }
        .mapValues { (_, tracks) -> tracks.firstOrNull() }

    private fun resolveBookId(
        tracker: List<SeriesWithBooks>,
        playerBooks: Map<String, PlayerLibraryItem?>,
        dir: String
    ): String? {
        val playerItem = playerBooks[normalizeDir(dir)] ?: return null
        val title = normalize(playerItem.bookTitle ?: dir.substringAfterLast('/'))
        val series = normalize(playerItem.series.orEmpty())
        val candidates = tracker.asSequence().flatMap { group ->
            group.books.asSequence().map { book -> group.series.name to book }
        }.filter { (_, book) -> normalize(book.title) == title }.toList()
        return (candidates.firstOrNull { (seriesName, _) -> series.isBlank() || normalize(seriesName) == series }
            ?: candidates.firstOrNull())?.second?.id
    }

    internal fun normalizeDir(value: String) = value.replace('\\', '/').trimEnd('/')

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ")
        .trim()
}
