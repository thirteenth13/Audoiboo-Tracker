package org.audoiboo.tracker

import java.net.URI

/** Request headers required by media hosts that validate the audiobook page origin. */
internal object DownloadRequestHeaderPolicy {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    fun headers(bookUrl: String, downloadUrl: String): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to bookUrl,
            "Accept" to "audio/mpeg,audio/*;q=0.9,application/octet-stream;q=0.8,*/*;q=0.5"
        )
        val book = parse(bookUrl)
        val download = parse(downloadUrl)
        if (book != null && download != null && isIzib(book.host.orEmpty()) && isIzibMedia(download.host.orEmpty())) {
            val port = if (book.port >= 0) ":${book.port}" else ""
            headers["Origin"] = "${book.scheme}://${book.host}$port"
        }
        return headers
    }

    private fun parse(url: String): URI? = runCatching { URI(url) }.getOrNull()

    private fun isIzib(host: String): Boolean {
        val value = host.lowercase()
        return value == "izib.uk" || value.endsWith(".izib.uk")
    }

    private fun isIzibMedia(host: String): Boolean {
        val value = host.lowercase()
        return isIzib(value) || value == "abookfiles.online" || value.endsWith(".abookfiles.online")
    }
}
