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
        val sanitized = sanitizeReason(reason)
        val old = read(pluginId, version)

        // Network/service failures are not evidence that an installed plugin package is corrupt.
        // Counting timeouts toward automatic quarantine can move the active package away while the
        // current plugin instance still points at its old directory; every later call then fails with
        // "Entrypoint file is missing". Preserve the existing structural-failure count, but do not
        // increment or quarantine for transient transport failures.
        if (isTransientTransportFailure(sanitized)) {
            return old ?: PluginRuntimeHealthState(
                failures = 0,
                lastFailureAt = clockMillis(),
                lastReason = sanitized
            )
        }

        val state = PluginRuntimeHealthState(
            failures = (old?.failures ?: 0) + 1,
            lastFailureAt = clockMillis(),
            lastReason = sanitized
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

    private fun isTransientTransportFailure(value: String): Boolean {
        val normalized = value.lowercase()
        return TRANSIENT_TRANSPORT_MARKERS.any(normalized::contains)
    }

    private fun sanitizeReason(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(2048)
        .ifBlank { "Plugin runtime failure" }

    private companion object {
        val TRANSIENT_TRANSPORT_MARKERS = listOf(
            "timeout",
            "timed out",
            "socket closed",
            "connection reset",
            "connection refused",
            "failed to connect",
            "unable to resolve host",
            "unknown host",
            "network is unreachable",
            "unexpected end of stream",
            "stream was reset"
        )
    }
}
