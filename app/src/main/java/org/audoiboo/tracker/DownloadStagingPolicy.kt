package org.audoiboo.tracker

/** Rules for reconciling persisted progress with the actual .part file after pause/process death. */
internal object DownloadStagingPolicy {
    fun shouldDiscard(stagingBytes: Long, knownTotal: Long): Boolean =
        stagingBytes < 0L || (knownTotal > 0L && stagingBytes > knownTotal)

    fun isComplete(stagingBytes: Long, knownTotal: Long): Boolean =
        stagingBytes > 0L && knownTotal > 0L && stagingBytes == knownTotal

    fun actualProgress(stagingBytes: Long, knownTotal: Long): Long =
        if (shouldDiscard(stagingBytes, knownTotal)) 0L else stagingBytes.coerceAtLeast(0L)
}
