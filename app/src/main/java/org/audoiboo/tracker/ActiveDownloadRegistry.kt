package org.audoiboo.tracker

import java.util.concurrent.ConcurrentHashMap

/**
 * Atomically owns active download executions.
 *
 * Registration happens before execution starts, so duplicate START/RESUME intents
 * cannot launch two writers for the same staging file. A small global cap prevents
 * many different downloads from exhausting threads, sockets, storage bandwidth and
 * foreground-service resources at the same time.
 */
internal class ActiveDownloadRegistry<T>(private val maxActive: Int = DEFAULT_MAX_ACTIVE) {
    companion object { const val DEFAULT_MAX_ACTIVE = 2 }

    init { require(maxActive > 0) { "maxActive must be positive" } }

    private val active = ConcurrentHashMap<String, T>()
    private val lock = Any()

    fun tryRegister(id: String, value: T): Boolean = synchronized(lock) {
        if (active.containsKey(id) || active.size >= maxActive) return@synchronized false
        active[id] = value
        true
    }

    fun unregister(id: String, value: T): Boolean = synchronized(lock) {
        active.remove(id, value)
    }

    fun isEmpty(): Boolean = active.isEmpty()

    fun contains(id: String): Boolean = active.containsKey(id)

    fun size(): Int = active.size
}
