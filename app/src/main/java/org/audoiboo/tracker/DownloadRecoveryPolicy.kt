package org.audoiboo.tracker

/** Rules for rebuilding persisted download intent after the app/service process is recreated. */
internal object DownloadRecoveryPolicy {
    fun shouldRecover(state: ManagedDownloadState): Boolean = state in setOf(
        ManagedDownloadState.QUEUED,
        ManagedDownloadState.DOWNLOADING,
        ManagedDownloadState.EXTRACTING
    )

    /** DOWNLOADING/EXTRACTING are runtime states; after process death they must return to QUEUED. */
    fun normalizedState(state: ManagedDownloadState): ManagedDownloadState = when (state) {
        ManagedDownloadState.DOWNLOADING,
        ManagedDownloadState.EXTRACTING -> ManagedDownloadState.QUEUED
        else -> state
    }

    fun workerCanKick(state: ManagedDownloadState): Boolean = DownloadControlPolicy.canStart(state)
}
