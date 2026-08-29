package org.audoiboo.tracker

/**
 * Converts a ZIP entry name into a safe relative path.
 * Absolute paths, drive-prefixed paths, traversal segments and NUL bytes are rejected.
 */
internal object ArchiveEntryPolicy {
    fun safeRelativePath(rawName: String): String? {
        if (rawName.isBlank() || '\u0000' in rawName) return null
        val normalized = rawName.replace('\\', '/')
        if (normalized.startsWith('/')) return null
        if (Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null

        val segments = normalized.split('/')
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.any { it == "." || it == ".." }) return null

        return segments.joinToString("/")
    }
}
