package org.audoiboo.tracker

internal object ManagedDownloadMigrationPolicy {
    fun payloadIsComplete(sourceCount: Int, validCount: Int): Boolean =
        sourceCount >= 0 && validCount >= 0 && sourceCount == validCount

    fun shouldRemoveLegacy(roomCount: Int, legacyParsed: Boolean): Boolean =
        roomCount > 0 || legacyParsed
}
