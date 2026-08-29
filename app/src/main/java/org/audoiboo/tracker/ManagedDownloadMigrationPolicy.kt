package org.audoiboo.tracker

internal object ManagedDownloadMigrationPolicy {
    fun shouldRemoveLegacy(roomCount: Int, legacyParsed: Boolean): Boolean =
        roomCount > 0 || legacyParsed
}
