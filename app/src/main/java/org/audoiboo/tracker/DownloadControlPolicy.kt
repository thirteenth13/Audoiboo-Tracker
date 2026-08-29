package org.audoiboo.tracker

/**
 * Centralizes which persisted download states may be started by service intents.
 * A stale WorkManager START must never revive a paused, cancelled, or completed transfer.
 */
internal object DownloadControlPolicy {
    fun canStart(state: ManagedDownloadState): Boolean = state in setOf(
        ManagedDownloadState.QUEUED,
        ManagedDownloadState.FAILED,
        ManagedDownloadState.DOWNLOADING,
        ManagedDownloadState.EXTRACTING
    )

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
