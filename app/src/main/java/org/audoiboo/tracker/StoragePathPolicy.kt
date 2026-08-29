package org.audoiboo.tracker

/** Pure path normalization used before writing through SAF. */
internal object StoragePathPolicy {
    fun normalizeRelativeDir(value: String): String? {
        val parts = value.replace('\\', '/').split('/').filter { it.isNotBlank() }
        if (parts.any { it == "." || it == ".." || it.contains('\u0000') }) return null
        return parts.joinToString("/")
    }

    fun validFileName(value: String): Boolean = value.isNotBlank() &&
        value != "." && value != ".." &&
        !value.contains('/') && !value.contains('\\') && !value.contains('\u0000')
}
