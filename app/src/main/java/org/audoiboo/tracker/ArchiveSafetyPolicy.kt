package org.audoiboo.tracker

/** Guards ZIP verification/extraction from pathological entry counts and expansion sizes. */
internal object ArchiveSafetyPolicy {
    const val MAX_FILES = 5_000
    const val MAX_UNCOMPRESSED_BYTES = 20L * 1024 * 1024 * 1024 // 20 GiB
    const val MAX_SINGLE_FILE_BYTES = 4L * 1024 * 1024 * 1024 // 4 GiB

    fun validateDeclaredEntrySize(size: Long): Boolean = size < 0 || size <= MAX_SINGLE_FILE_BYTES

    fun validateTotals(files: Int, uncompressedBytes: Long): Boolean =
        files <= MAX_FILES && uncompressedBytes <= MAX_UNCOMPRESSED_BYTES
}
