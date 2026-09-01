package org.audoiboo.tracker.plugin

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class PluginDiagnosticSnapshot(
    val pluginId: String,
    val version: Int?,
    val updatedAt: String,
    val entries: List<String>
) {
    fun report(): String = buildString {
        append(pluginId)
        version?.let { append(" v").append(it) }
        append("\n").append(updatedAt)
        if (entries.isEmpty()) append("\nНемає діагностичних подій")
        else entries.forEach { append("\n• ").append(it) }
    }
}

/** In-memory, bounded diagnostics owned by the host runtime. No page bodies or cookies are retained. */
object PluginDiagnostics {
    private const val MAX_ENTRIES = 80
    private val entries = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val versions = ConcurrentHashMap<String, Int>()
    private val updated = ConcurrentHashMap<String, String>()

    @Synchronized
    fun record(pluginId: String, version: Int?, message: String) {
        val safe = message.replace(Regex("[\\r\\n]+"), " ").take(500)
        val queue = entries.getOrPut(pluginId) { ArrayDeque() }
        if (queue.size >= MAX_ENTRIES) queue.removeFirst()
        queue.addLast(safe)
        version?.let { versions[pluginId] = it }
        updated[pluginId] = Instant.now().toString()
    }

    @Synchronized
    fun clear(pluginId: String) {
        entries.remove(pluginId)
        versions.remove(pluginId)
        updated.remove(pluginId)
    }

    @Synchronized
    fun snapshot(pluginId: String): PluginDiagnosticSnapshot = PluginDiagnosticSnapshot(
        pluginId = pluginId,
        version = versions[pluginId],
        updatedAt = updated[pluginId] ?: "Ще не запускалось",
        entries = entries[pluginId]?.toList().orEmpty()
    )
}