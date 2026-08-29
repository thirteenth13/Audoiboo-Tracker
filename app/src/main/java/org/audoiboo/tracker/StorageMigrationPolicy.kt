package org.audoiboo.tracker

/** Pure decisions for safely re-running SAF library migrations. */
internal object StorageMigrationPolicy {
    fun canReuseExisting(sourceUri: String, targetUri: String, sourceBytes: Long?, targetBytes: Long): Boolean {
        if (sourceUri.isNotBlank() && sourceUri == targetUri) return true
        return sourceBytes != null && sourceBytes > 0L && targetBytes > 0L && sourceBytes == targetBytes
    }

    fun normalizedDestination(path: String): String? {
        val normalized = StoragePathPolicy.normalizeRelativeDir(path) ?: return null
        val parts = normalized.split('/').filter { it.isNotBlank() }.toMutableList()
        if (parts.firstOrNull()?.equals("Download", ignoreCase = true) == true) parts.removeAt(0)
        return parts.joinToString("/")
    }
}
