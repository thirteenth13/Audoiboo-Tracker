package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object RoomCoverSync {
    private const val INDEX_PREFS = "cover_index"

    suspend fun enqueueAll(context: Context) = withContext(Dispatchers.IO) {
        val library = LibraryRepository.snapshot(context)
        val editor = context.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE).edit().clear()
        library.forEach { group ->
            val series = normalize(group.series.name)
            group.books.forEach { book ->
                val url = book.coverUrl?.takeIf { it.startsWith("http", true) } ?: return@forEach
                val title = normalize(book.title)
                editor.putString("$series|$title", url)
                editor.putString("title|$title", url)
                CoverCache.enqueue(context, url)
            }
        }
        editor.apply()
        CoverCache.prune(context)
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
