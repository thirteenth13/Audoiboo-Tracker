package org.audoiboo.tracker

import java.net.URI

/**
 * Chooses the persisted file extension for both archive and direct-file download candidates.
 * Legacy archive URLs without a recognizable suffix keep the historic .zip fallback.
 */
internal object DownloadFilePolicy {
    private val supportedExtensions = setOf(
        "zip", "rar", "7z",
        "mp3", "m4b", "m4a", "aac", "ogg", "opus", "flac", "wav"
    )

    fun extension(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val suffix = path.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
        return if (suffix in supportedExtensions) ".$suffix" else ".zip"
    }

    fun isDirectAudio(url: String): Boolean = extension(url) in setOf(
        ".mp3", ".m4b", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".wav"
    )
}
