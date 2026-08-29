package org.audoiboo.tracker

/** Pure rules for locating a replacement URI when a restored library URI is no longer readable. */
internal object LibraryUriRecoveryPolicy {
    fun safRelativeDir(relativePath: String): String? =
        StorageMigrationPolicy.normalizedDestination(relativePath)

    fun mediaStoreRelativeDirs(relativePath: String): List<String> {
        val normalized = StoragePathPolicy.normalizeRelativeDir(relativePath) ?: return emptyList()
        val withoutDownload = StorageMigrationPolicy.normalizedDestination(relativePath) ?: return emptyList()
        return linkedSetOf(
            normalized.trim('/'),
            "Download/${withoutDownload.trim('/')}".trim('/'),
            withoutDownload.trim('/')
        ).filter { it.isNotBlank() }
    }
}
