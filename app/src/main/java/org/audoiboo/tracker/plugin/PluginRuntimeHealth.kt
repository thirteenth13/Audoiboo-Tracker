package org.audoiboo.tracker.plugin

import java.io.File
import java.io.IOException

/** Durable per-version runtime failure counter used to quarantine repeatedly crashing plugins. */
data class PluginRuntimeHealthState(
    val failures: Int,
    val lastFailureAt: Long,
    val lastReason: String
)

class PluginRuntimeHealth(
    private val root: File,
    val failureThreshold: Int = 3,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
    }

    @Synchronized
    fun recordFailure(pluginId: String, version: Int, reason: String): PluginRuntimeHealthState {
        val old = read(pluginId, version)
        val state = PluginRuntimeHealthState(
            failures = (old?.failures ?: 0) + 1,
            lastFailureAt = clockMillis(),
            lastReason = sanitizeReason(reason)
        )
        write(pluginId, version, state)
        return state
    }

    @Synchronized
    fun recordSuccess(pluginId: String, version: Int) {
        stateFile(pluginId, version).delete()
    }

    @Synchronized
    fun read(pluginId: String, version: Int): PluginRuntimeHealthState? {
        val file = stateFile(pluginId, version)
        if (!file.isFile) return null
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        if (lines.size < 3) return null
        val failures = lines[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val at = lines[1].toLongOrNull() ?: return null
        return PluginRuntimeHealthState(failures, at, lines.drop(2).joinToString("\n").take(2048))
    }

    @Synchronized
    fun clear(pluginId: String, version: Int? = null) {
        if (version != null) {
            stateFile(pluginId, version).delete()
            return
        }
        File(root, pluginId).deleteRecursively()
    }

    fun shouldQuarantine(state: PluginRuntimeHealthState): Boolean = state.failures >= failureThreshold

    private fun write(pluginId: String, version: Int, state: PluginRuntimeHealthState) {
        val target = stateFile(pluginId, version)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.tmp")
        temp.writeText("${state.failures}\n${state.lastFailureAt}\n${state.lastReason}")
        if (target.exists() && !target.delete()) throw IOException("Unable to replace plugin runtime health state")
        if (!temp.renameTo(target)) throw IOException("Unable to persist plugin runtime health state")
    }

    private fun stateFile(pluginId: String, version: Int): File = File(root, "$pluginId/$version.failures")

    private fun sanitizeReason(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(2048)
        .ifBlank { "Plugin runtime failure" }
}
