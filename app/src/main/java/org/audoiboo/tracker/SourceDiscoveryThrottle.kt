package org.audoiboo.tracker

internal object SourceDiscoveryThrottle {
    /**
     * Series refresh is an explicit user action and is expected to re-check all enabled providers.
     * A previous empty discovery result must not hide newly fixed/enabled plugins for six hours.
     * Callers that really need throttling can still pass an explicit intervalMs.
     */
    const val DEFAULT_INTERVAL_MS: Long = 0L

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
