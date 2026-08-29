package org.audoiboo.tracker

import java.util.concurrent.ConcurrentHashMap

/**
 * Atomically owns one active execution per download id.
 *
 * Registration happens before the execution is started, so two START/RESUME
 * intents cannot launch two writers for the same staging file.
 */
internal class ActiveDownloadRegistry<T> {
    private val active = ConcurrentHashMap<String, T>()

    fun tryRegister(id: String, value: T): Boolean = active.putIfAbsent(id, value) == null

    fun unregister(id: String, value: T): Boolean = active.remove(id, value)

    fun isEmpty(): Boolean = active.isEmpty()

    fun contains(id: String): Boolean = active.containsKey(id)
}
