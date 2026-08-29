package org.audoiboo.tracker

/**
 * Centralizes persisted download state transitions. Automatic starts are deliberately stricter
 * than an explicit user retry: FAILED must first be re-queued by user action or retry policy.
 */
internal object DownloadControlPolicy {
    fun canAutoStart(state: ManagedDownloadState): Boolean = state in setOf(
        ManagedDownloadState.QUEUED,
        ManagedDownloadState.DOWNLOADING,
        ManagedDownloadState.EXTRACTING
    )

    fun canManualStart(state: ManagedDownloadState): Boolean =
        canAutoStart(state) || state == ManagedDownloadState.FAILED

    /** Service START is treated as automatic because Android may redeliver stale intents. */
    fun canStart(state: ManagedDownloadState): Boolean = canAutoStart(state)

    fun pause(state: ManagedDownloadState): ManagedDownloadState = when (state) {
        ManagedDownloadState.COMPLETED, ManagedDownloadState.CANCELLED -> state
        else -> ManagedDownloadState.PAUSED
    }

    fun cancel(state: ManagedDownloadState): ManagedDownloadState = when (state) {
        ManagedDownloadState.COMPLETED -> state
        else -> ManagedDownloadState.CANCELLED
    }

    fun resume(state: ManagedDownloadState): ManagedDownloadState = when (state) {
        ManagedDownloadState.PAUSED, ManagedDownloadState.FAILED -> ManagedDownloadState.QUEUED
        else -> state
    }
}
