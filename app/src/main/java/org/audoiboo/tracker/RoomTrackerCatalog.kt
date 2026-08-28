package org.audoiboo.tracker

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class RoomTrackerEntry(
    val bookId: String,
    val title: String,
    val series: String,
    val index: Int,
    val coverUrl: String?,
    val status: String?
)

/** In-memory projection of the Room tracker library for latency-sensitive player metadata lookups. */
internal object RoomTrackerCatalog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()
    @Volatile private var entries: List<RoomTrackerEntry> = emptyList()
    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val app = context.applicationContext
            scope.launch {
                LibraryRepository.observe(app).collect { library ->
                    entries = library.flatMap { group ->
                        group.books.sortedBy { it.sortIndex }.map { book ->
                            RoomTrackerEntry(
                                bookId = book.id,
                                title = book.title,
                                series = group.series.name,
                                index = book.sortIndex,
                                coverUrl = book.coverUrl,
                                status = book.status
                            )
                        }
                    }
                    _revision.value = _revision.value + 1L
                }
            }
        }
    }

    fun snapshot(): List<RoomTrackerEntry> = entries

    fun syncStatuses(context: Context, updates: List<Pair<String, String>>) {
        if (updates.isEmpty()) return
        val app = context.applicationContext
        val byTitle = updates.associate { normalize(it.first) to it.second }
        scope.launch {
            val dao = AudoibooDatabase.get(app).libraryDao()
            val current = entries
            current.forEach { entry ->
                val status = byTitle[normalize(entry.title)] ?: return@forEach
                if (!entry.status.equals(status, true)) dao.updateBookStatus(entry.bookId, status)
            }
        }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^a-zа-яіїєґ0-9]+"), " ")
        .trim()
}
