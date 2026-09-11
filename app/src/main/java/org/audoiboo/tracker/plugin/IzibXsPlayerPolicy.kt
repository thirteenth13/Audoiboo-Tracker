package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Tolerant parser for Izib XSPlayer configuration. */
object IzibXsPlayerPolicy {
    fun extract(config: JSONObject, pageUrl: String, allowedHosts: Set<String>): List<String> {
        val prefixRaw = config.optString("mp3_url_prefix")
            .replace("\\/", "/")
            .trim()
            .trimEnd('/')
        val prefix = resolvePrefix(pageUrl, prefixRaw) ?: return emptyList()
        val sign = config.optString("sign").replace("\\/", "/").trim()
        val tracks = config.optJSONArray("tracks") ?: return emptyList()

        val out = linkedSetOf<String>()
        for (i in 0 until tracks.length()) {
            val raw = trackFile(tracks.opt(i)) ?: continue
            val resolved = resolveTrack(prefix, raw) ?: continue
            val signed = appendSign(resolved, sign)
            if (isHttpAudio(signed) && hostAllowed(signed, allowedHosts)) out += signed
        }
        return out.toList()
    }

    internal fun trackFile(value: Any?): String? = when (value) {
        is JSONArray -> listOf(4, 3, 2, 1, 0)
            .asSequence()
            .mapNotNull { idx -> value.optString(idx).cleanMediaCandidate() }
            .firstOrNull()
        is JSONObject -> listOf("file", "src", "url", "path")
            .asSequence()
            .mapNotNull { key -> value.optString(key).cleanMediaCandidate() }
            .firstOrNull()
        is String -> value.cleanMediaCandidate()
        else -> null
    }

    internal fun appendSign(url: String, sign: String): String {
        if (sign.isBlank()) return url
        if (url.endsWith(sign)) return url
        return when {
            sign.startsWith("?") && url.contains('?') -> url + "&" + sign.removePrefix("?")
            sign.startsWith("&") && !url.contains('?') -> url + "?" + sign.removePrefix("&")
            else -> url + sign
        }
    }

    private fun resolvePrefix(pageUrl: String, raw: String): String? = runCatching {
        if (raw.isBlank()) return@runCatching null
        when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> URI(pageUrl).scheme + ":" + raw
            raw.startsWith("/") -> URI(pageUrl).resolve(raw).toString().trimEnd('/')
            HOST_LIKE.matches(raw) -> "https://$raw"
            else -> URI(pageUrl).resolve(raw).toString().trimEnd('/')
        }
    }.getOrNull()

    private fun resolveTrack(prefix: String, raw: String): String? = runCatching {
        val cleaned = raw.replace("\\/", "/").trim()
        if (cleaned.isBlank()) return@runCatching null
        when {
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            cleaned.startsWith("//") -> URI(prefix).scheme + ":" + cleaned
            else -> URI(prefix.trimEnd('/') + "/").resolve(cleaned).toString()
        }
    }.getOrNull()

    private fun String.cleanMediaCandidate(): String? {
        val value = replace("\\/", "/").trim()
        if (value.isBlank()) return null
        val path = runCatching { URI(value).path }.getOrNull() ?: value.substringBefore('?')
        val ext = path.substringAfterLast('.', "").lowercase()
        return value.takeIf { ext in AUDIO_EXTENSIONS }
    }

    private fun isHttpAudio(url: String): Boolean = runCatching {
        val uri = URI(url)
        val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        uri.scheme?.lowercase() in setOf("http", "https") && ext in AUDIO_EXTENSIONS
    }.getOrDefault(false)

    private fun hostAllowed(url: String, allowed: Set<String>): Boolean = runCatching {
        val host = URI(url).host?.lowercase().orEmpty()
        allowed.any { candidate ->
            val normalized = candidate.lowercase()
            host == normalized || host.endsWith(".$normalized")
        }
    }.getOrDefault(false)

    private val HOST_LIKE = Regex("^[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(?::\\d+)?(?:/.*)?$")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac")
}
