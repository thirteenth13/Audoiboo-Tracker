package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

internal object BazaPlaylistPolicy {
    private val mediaExtensions = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac")

    fun extract(responseBody: String, baseUrl: String, allowedHosts: Set<String>): List<String> {
        val out = linkedSetOf<String>()

        fun resolve(raw: String?): String? = runCatching {
            val value = raw?.trim()?.trim('"', '\'')?.replace("\\/", "/").orEmpty()
            if (value.isBlank()) return@runCatching null
            URI(baseUrl).resolve(value).toString()
        }.getOrNull()

        fun allowed(url: String): Boolean = runCatching {
            val uri = URI(url)
            val host = uri.host?.lowercase().orEmpty()
            val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
            uri.scheme?.lowercase() in setOf("http", "https") &&
                ext in mediaExtensions &&
                allowedHosts.any { a -> host == a.lowercase() || host.endsWith(".${a.lowercase()}") }
        }.getOrDefault(false)

        fun add(raw: String?) {
            val url = resolve(raw) ?: return
            if (allowed(url)) out += url
        }

        fun collect(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i))
                is JSONObject -> {
                    listOf("src", "url", "file", "path").forEach { key ->
                        value.optString(key).takeIf { it.isNotBlank() }?.let(::add)
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) collect(value.opt(keys.next()))
                }
                is String -> add(value)
            }
        }

        val parsed: Any? = runCatching { JSONArray(responseBody) }.getOrNull()
            ?: runCatching { JSONObject(responseBody) }.getOrNull()
        if (parsed != null) collect(parsed)

        if (out.isEmpty()) {
            val normalized = responseBody.replace("\\/", "/")
            Regex("(?i)(?:https?:)?//[^\\s\\\"'<>]+\\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\\?[^\\s\\\"'<>]*)?|(?:^|[\\\"'\\s=(:,])((?:\\.{0,2}/|/)[^\\s\\\"'<>]+\\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\\?[^\\s\\\"'<>]*)?)")
                .findAll(normalized)
                .forEach { match -> add(match.groupValues.getOrNull(1).takeUnless { it.isNullOrBlank() } ?: match.value.trimStart('"', '\'', ' ', '=', '(', ':', ',')) }
        }

        return out.toList()
    }
}
