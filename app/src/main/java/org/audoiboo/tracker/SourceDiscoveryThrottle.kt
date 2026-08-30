package org.audoiboo.tracker

internal object SourceDiscoveryThrottle {
    const val DEFAULT_INTERVAL_MS: Long = 6L * 60L * 60L * 1000L

    fun shouldRun(
        lastSuccessAt: Long,
        now: Long,
        force: Boolean = false,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ): Boolean {
        if (force) return true
        if (lastSuccessAt <= 0L) return true
        if (now < lastSuccessAt) return true
        return now - lastSuccessAt >= intervalMs.coerceAtLeast(0L)
    }
}
