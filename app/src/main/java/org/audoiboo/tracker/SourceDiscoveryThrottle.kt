package org.audoiboo.tracker

internal object SourceDiscoveryThrottle {
    /**
     * Prevent only an immediate duplicate pass from the same refresh pipeline. A real user refresh
     * a few seconds later still re-checks every enabled provider, so fixed/enabled plugins are not
     * hidden behind a long cache interval.
     */
    const val DEFAULT_INTERVAL_MS: Long = 5_000L

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
