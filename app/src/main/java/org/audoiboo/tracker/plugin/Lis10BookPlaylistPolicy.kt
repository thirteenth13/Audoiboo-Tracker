package org.audoiboo.tracker.plugin

import org.json.JSONObject
import java.net.URI

internal object Lis10BookPlaylistPolicy {
    private val audioExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac")

    fun extract(responseBody: String, baseUrl: String, allowedHosts: Set<String>): List<String> {
        val chapters = runCatching { JSONObject(responseBody).optJSONArray("chapters") }.getOrNull() ?: return emptyList()
        return buildList<Pair<Int, String>> {
            for (i in 0 until chapters.length()) {
                val item = chapters.optJSONObject(i) ?: continue
                val raw = item.optString("src").trim().replace("\\/", "/")
                val url = resolve(baseUrl, raw) ?: continue
                if (!isAudio(url) || !hostAllowed(url, allowedHosts)) continue
                add(item.optInt("position", i + 1) to url)
            }
        }.sortedBy { it.first }.map { it.second }.distinct()
    }

    private fun resolve(baseUrl: String, raw: String): String? = runCatching {
        if (raw.isBlank()) return@runCatching null
        URI(baseUrl).resolve(raw).toString()
    }.getOrNull()

    private fun isAudio(url: String): Boolean = runCatching {
        val uri = URI(url)
        val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        uri.scheme?.lowercase() in setOf("http", "https") && ext in audioExtensions
    }.getOrDefault(false)

    private fun hostAllowed(url: String, allowedHosts: Set<String>): Boolean = runCatching {
        val host = URI(url).host?.lowercase().orEmpty()
        allowedHosts.any { allowed ->
            val normalized = allowed.lowercase()
            host == normalized || host.endsWith(".$normalized")
        }
    }.getOrDefault(false)
}
