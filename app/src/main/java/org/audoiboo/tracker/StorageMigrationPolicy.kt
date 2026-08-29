package org.audoiboo.tracker

/** Pure decisions for safely re-running SAF library migrations. */
internal object StorageMigrationPolicy {
    fun canReuseExisting(sourceUri: String, targetUri: String, sourceBytes: Long?, targetBytes: Long): Boolean {
        // Equal byte length is not proof that two audio files are identical. Reuse only when the
        // library already points at the exact destination document; otherwise treat it as a
        // collision so migration never silently binds playback state to different content.
        return sourceUri.isNotBlank() && sourceUri == targetUri
    }

    fun normalizedDestination(path: String): String? {
        val normalized = StoragePathPolicy.normalizeRelativeDir(path) ?: return null
        val parts = normalized.split('/').filter { it.isNotBlank() }.toMutableList()
        if (parts.firstOrNull()?.equals("Download", ignoreCase = true) == true) parts.removeAt(0)
        return parts.joinToString("/")
    }
}
