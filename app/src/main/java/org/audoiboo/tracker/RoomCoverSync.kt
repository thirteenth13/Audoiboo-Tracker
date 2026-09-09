package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RoomCoverSync {
    private const val INDEX_PREFS = "cover_index"

    suspend fun enqueueAll(context: Context) = withContext(Dispatchers.IO) {
        // A source refresh may have learned the same logical book from several providers using
        // different title/author formatting. Repair those provider-backed Room aliases before the
        // auxiliary cover pass so the UI immediately sees one canonical row with many sources.
        try {
            RoomBookDeduplication.repairAll(context)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }

        // FantLab may expose a subcycle inside a parent cycle. Once book-level repair has removed
        // those nested works from the parent, persist the subcycle as its own catalog-backed series.
        try {
            FantLabNestedSeriesSync.syncAll(context)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }

        // Cover/metadata enrichment is auxiliary work. A provider refresh may already have
        // discovered and persisted valid source matches, so enrichment failures must not turn the
        // whole series refresh into a false "Не вдалося оновити серію" result.
        try {
            BookMetadataEnrichment.enrichPending(context, limit = 8)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }

        val library = try {
            LibraryRepository.snapshot(context)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return@withContext
        }

        val editor = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE).edit().clear()
        library.forEach { group ->
            val series = normalize(group.series.name)
            group.books.forEach { book ->
                val url = book.coverUrl?.takeIf { it.startsWith("http", true) } ?: return@forEach
                val title = normalize(book.title)
                editor.putString("$series|$title", url)
                editor.putString("title|$title", url)
                try {
                    CoverCache.enqueue(context, url)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                }
            }
        }
        editor.apply()
        try {
            CoverCache.prune(context)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }
    }

    fun lookup(context: Context, series: String?, title: String): String? {
        val prefs = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE)
        val normalizedTitle = normalize(title)
        val normalizedSeries = normalize(series.orEmpty())
        return prefs.getString("$normalizedSeries|$normalizedTitle", null)
            ?: prefs.getString("title|$normalizedTitle", null)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ")
        .trim()
}
