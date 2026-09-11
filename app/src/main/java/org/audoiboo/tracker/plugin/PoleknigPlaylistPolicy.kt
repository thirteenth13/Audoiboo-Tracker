package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Tolerant parser for Poleknig player playlist payload variants. */
internal object PoleknigPlaylistPolicy {
    fun extract(raw: String, baseUrl: String): List<String> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        val array = parseArray(text) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val candidate = when (val item = array.opt(i)) {
                    is JSONObject -> listOf("file", "src", "url")
                        .asSequence()
                        .map { item.optString(it).trim().replace("\\/", "/") }
                        .firstOrNull { it.isNotBlank() }
                    is String -> item.trim().replace("\\/", "/")
                    else -> null
                } ?: continue
                val first = candidate.split(Regex("\\s+or\\s+", RegexOption.IGNORE_CASE), limit = 2)
                    .firstOrNull()?.trim().orEmpty()
                resolve(baseUrl, first)?.let(::add)
            }
        }.distinct()
    }

    private fun parseArray(text: String): JSONArray? {
        runCatching { JSONArray(text) }.getOrNull()?.let { return it }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        for (key in listOf("playlist", "tracks", "items", "data")) {
            root.optJSONArray(key)?.let { return it }
        }
        return null
    }

    private fun resolve(baseUrl: String, raw: String): String? = runCatching {
        if (raw.isBlank()) return@runCatching null
        val url = URI(baseUrl).resolve(raw).toString()
        val uri = URI(url)
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return@runCatching null
        url
    }.getOrNull()
}
