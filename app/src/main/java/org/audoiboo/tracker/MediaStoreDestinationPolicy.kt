package org.audoiboo.tracker

/**
 * Android 10+ MediaStore constrains Audio.Media rows to audio primary directories.
 * Non-audio payloads remain in Download, while playable files are published under Audiobooks.
 */
internal object MediaStoreDestinationPolicy {
    const val DOWNLOAD_ROOT = "Download"
    const val AUDIOBOOK_ROOT = "Audiobooks"

    fun root(isAudio: Boolean): String = if (isAudio) AUDIOBOOK_ROOT else DOWNLOAD_ROOT

    fun relativePath(relativeDir: String, isAudio: Boolean): String =
        listOf(root(isAudio), relativeDir.trim('/'))
            .filter(String::isNotBlank)
            .joinToString("/")
}
