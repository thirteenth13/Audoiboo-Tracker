package org.audoiboo.tracker

/**
 * Centralizes persisted download state transitions. Automatic WorkManager kicks are deliberately
 * stricter than an explicit user resume: FAILED must not be revived by a stale worker forever.
 */
internal object DownloadControlPolicy {
    fun canAutoStart(state: ManagedDownloadState): Boolean = state in setOf(
        ManagedDownloadState.QUEUED,
        ManagedDownloadState.DOWNLOADING,
        ManagedDownloadState.EXTRACTING
    )

    fun canManualStart(state: ManagedDownloadState): Boolean =
        canAutoStart(state) || state == ManagedDownloadState.FAILED

    /** Compatibility alias for service-side explicit START/RESUME checks. */
    fun canStart(state: ManagedDownloadState): Boolean = canManualStart(state)

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
