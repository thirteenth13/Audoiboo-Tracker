package org.audoiboo.tracker

/** Pure selection rules for choosing a SAF tree after settings/backup restore. */
internal object StorageTreePolicy {
    fun select(
        restoredRaw: String?,
        restoredAllowed: Boolean,
        fallbackRaw: String?,
        fallbackAllowed: Boolean
    ): String? = when {
        !restoredRaw.isNullOrBlank() && restoredAllowed -> restoredRaw
        !fallbackRaw.isNullOrBlank() && fallbackAllowed -> fallbackRaw
        else -> null
    }
}
