package org.audoiboo.tracker

import java.util.ArrayDeque

/**
 * Keeps at most [maxConcurrent] download ids active without creating blocked waiting threads.
 * Extra ids stay in a small in-memory FIFO and are promoted when an active transfer finishes.
 */
internal class DownloadStartCoordinator(private val maxConcurrent: Int) {
    private val active = LinkedHashSet<String>()
    private val queued = ArrayDeque<String>()
    private val queuedSet = HashSet<String>()
    private val limit = maxConcurrent.coerceAtLeast(1)

    @Synchronized
    fun request(id: String): Boolean {
        if (id in active) return false
        if (id in queuedSet) return false
        if (active.size < limit) {
            active += id
            return true
        }
        queued += id
        queuedSet += id
        return false
    }

    @Synchronized
    fun cancelQueued(id: String): Boolean {
        if (!queuedSet.remove(id)) return false
        queued.remove(id)
        return true
    }

    /** Releases [id] and atomically reserves the freed slot for the next queued id. */
    @Synchronized
    fun finished(id: String): String? {
        if (!active.remove(id)) return null
        while (queued.isNotEmpty()) {
            val next = queued.removeFirst()
            queuedSet.remove(next)
            if (active.add(next)) return next
        }
        return null
    }

    @Synchronized fun isActive(id: String): Boolean = id in active
    @Synchronized fun isQueued(id: String): Boolean = id in queuedSet
    @Synchronized fun activeCount(): Int = active.size
    @Synchronized fun queuedCount(): Int = queued.size
}
