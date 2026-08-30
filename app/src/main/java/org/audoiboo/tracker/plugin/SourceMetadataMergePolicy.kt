package org.audoiboo.tracker.plugin

internal object SourceMetadataMergePolicy {
    fun userVerified(existing: Boolean?, incoming: Boolean): Boolean =
        existing == true || incoming
}
