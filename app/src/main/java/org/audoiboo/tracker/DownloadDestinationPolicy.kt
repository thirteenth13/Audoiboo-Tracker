package org.audoiboo.tracker

/**
 * Pure destination policy shared by the download service and JVM tests.
 * Every completed payload belongs to the book directory, regardless of whether
 * it is a direct audio file, an archive kept as-is, or extracted ZIP contents.
 */
internal object DownloadDestinationPolicy {
    fun bookRelativeDir(record: ManagedDownloadRecord): String =
        listOf(record.relativeDir.trimEnd('/'), record.bookDir.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")

    fun shouldExtract(fileName: String, unpackEnabled: Boolean): Boolean =
        unpackEnabled && fileName.endsWith(".zip", ignoreCase = true)
}
