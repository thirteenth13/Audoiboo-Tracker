package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Host-owned direct media resolver for sites that expose a complete player playlist.
 * The site-specific formats were verified against the live players; callers keep their
 * existing WebView/sequential resolvers as a fallback when a site changes.
 */
object DirectSiteMediaResolver {
    data class Result(val mediaUrls: List<String>, val diagnostics: List<String>)

    fun resolve(manifest: PluginPackageManifest, pageUrl: String): Result? = runCatching {
        when (manifest.id) {
            "baza-knig" -> resolveBaza(manifest, pageUrl)
            "knigavuhe" -> resolveKnigavuhe(manifest, pageUrl)
            "izib" -> resolveIzib(manifest, pageUrl)
            "lis10book" -> resolveLis10book(manifest, pageUrl)
            else -> null
        }
    }.getOrNull()?.takeIf { it.mediaUrls.isNotEmpty() }

    private fun resolveBaza(manifest: PluginPackageManifest, pageUrl: String): Result? {
        val page = get(pageUrl) ?: return null
        if (page.statusCode !in 200..299) return null
        val playlistUrl = extractBazaPlaylistUrl(page.body, page.finalUrl) ?: return null
        if (!hostAllowed(playlistUrl, manifest.permissions.effectiveDownloadHosts + setOf("redirectto.cc"))) return null
        val playlist = get(playlistUrl) ?: return null
        if (playlist.statusCode !in 200..299) return null
        val urls = BazaPlaylistPolicy.extract(
            responseBody = playlist.body,
            baseUrl = playlist.finalUrl,
            allowedHosts = manifest.permissions.effectiveDownloadHosts
        )
        if (urls.isEmpty()) return null
        return Result(urls, listOf(
            "baza-playlist-url=${playlistUrl.take(500)}",
            "baza-playlist=${urls.size}",
            "media=${urls.size}"
        ))
    }

    private fun resolveKnigavuhe(manifest: PluginPackageManifest, pageUrl: String): Result? {
        val page = get(pageUrl) ?: return null
        if (page.statusCode !in 200..299) return null

        extractKnigavuheMergedPlaylist(page.body)?.let { merged ->
            val urls = extractKnigavuheUrls(manifest, merged, page.finalUrl)
            if (urls.isNotEmpty()) {
                return Result(urls, listOf(
                    "knigavuhe-source=page-merged-playlist",
                    "knigavuhe-merged=${merged.length()}",
                    "media=${urls.size}"
                ))
            }
        }

        val bookId = extractKnigavuheBookId(page.body) ?: return null
        val origin = URI(page.finalUrl)
        val base = "${origin.scheme}://${origin.authority}"
        val playUrl = "$base/play/id/$bookId"
        val playResponse = get(
            playUrl,
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json,text/plain,*/*"
            )
        )
        if (playResponse != null && playResponse.statusCode in 200..299) {
            val urls = extractJsonMedia(playResponse.body, playResponse.finalUrl)
                .filter { isHttpMedia(it) && hostAllowed(it, manifest.permissions.effectiveDownloadHosts) }
                .distinct()
            if (urls.isNotEmpty()) {
                return Result(urls, listOf(
                    "knigavuhe-source=play-api",
                    "knigavuhe-book-id=$bookId",
                    "knigavuhe-play-url=$playUrl",
                    "media=${urls.size}"
                ))
            }
        }

        val apiUrl = "$base/ajax/book_data/$bookId/"
        val response = get(apiUrl) ?: return null
        if (response.statusCode !in 200..299) return null
        val root = runCatching { JSONArray(response.body) }.getOrNull()
        val init = root?.optJSONObject(1)?.optJSONObject("result")?.optJSONObject("init_data")
        val shortCount = init?.optJSONArray("playlist")?.length() ?: 0
        val merged = init?.optJSONArray("merged_playlist")
        if (merged != null) {
            val urls = extractKnigavuheUrls(manifest, merged, response.finalUrl)
            if (urls.isNotEmpty()) {
                return Result(urls, listOf(
                    "knigavuhe-source=ajax-book-data",
                    "knigavuhe-book-id=$bookId",
                    "knigavuhe-playlist=$shortCount",
                    "knigavuhe-merged=${merged.length()}",
                    "media=${urls.size}"
                ))
            }
        }

        val generic = extractJsonMedia(response.body, response.finalUrl)
            .filter { isHttpMedia(it) && hostAllowed(it, manifest.permissions.effectiveDownloadHosts) }
            .distinct()
        return generic.takeIf { it.isNotEmpty() }?.let {
            Result(it, listOf(
                "knigavuhe-source=ajax-generic",
                "knigavuhe-book-id=$bookId",
                "media=${it.size}"
            ))
        }
    }

    private fun extractKnigavuheUrls(
        manifest: PluginPackageManifest,
        merged: JSONArray,
        baseUrl: String
    ): List<String> = buildList {
        for (i in 0 until merged.length()) {
            when (val item = merged.opt(i)) {
                is JSONObject -> {
                    if (item.optInt("error", 0) != 0) continue
                    val raw = firstHttpString(item, "url", "src", "file")
                        ?: listOf("url", "src", "file", "path")
                            .asSequence()
                            .map { item.optString(it).trim().replace("\\/", "/") }
                            .firstOrNull { it.isNotBlank() }
                    val url = resolveUrl(baseUrl, raw) ?: continue
                    if (isHttpMedia(url) && hostAllowed(url, manifest.permissions.effectiveDownloadHosts)) add(url)
                }
                is String -> {
                    val url = resolveUrl(baseUrl, item) ?: continue
                    if (isHttpMedia(url) && hostAllowed(url, manifest.permissions.effectiveDownloadHosts)) add(url)
                }
            }
        }
    }.distinct()

    private fun resolveIzib(manifest: PluginPackageManifest, pageUrl: String): Result? {
        val page = get(pageUrl) ?: return null
        if (page.statusCode !in 200..299) return null
        val configText = extractBalancedObjectAfter(page.body, "new XSPlayer(") ?: return null
        val cfg = JSONObject(configText)
        val tracks = cfg.optJSONArray("tracks") ?: return null
        val urls = IzibXsPlayerPolicy.extract(
            config = cfg,
            pageUrl = page.finalUrl,
            allowedHosts = manifest.permissions.effectiveDownloadHosts
        )
        if (urls.isEmpty()) return null
        return Result(urls, listOf(
            "izib-xsplayer-tracks=${tracks.length()}",
            "izib-xsplayer-tolerant=true",
            "media=${urls.size}"
        ))
    }

    private fun resolveLis10book(manifest: PluginPackageManifest, pageUrl: String): Result? {
        val uri = URI(pageUrl)
        val match = Regex("^/audio/([^/]+)/?").find(uri.path.orEmpty()) ?: return null
        val slug = match.groupValues[1]
        val apiUrl = "${uri.scheme}://${uri.authority}/api/p/$slug"
        val response = get(apiUrl) ?: return null
        if (response.statusCode !in 200..299) return null
        val ordered = Lis10BookPlaylistPolicy.extract(
            responseBody = response.body,
            baseUrl = response.finalUrl,
            allowedHosts = manifest.permissions.effectiveDownloadHosts
        )
        if (ordered.isEmpty()) return null
        return Result(ordered, listOf(
            "lis10book-api=/api/p/$slug",
            "media=${ordered.size}"
        ))
    }

    private fun get(url: String, headers: Map<String, String> = emptyMap()): PluginHttpResponse? = runCatching {
        HostPluginHttpTransport.get(PluginHttpRequest(url, headers), 8L * 1024L * 1024L)
    }.getOrNull()

    private fun extractBazaPlaylistUrl(html: String, baseUrl: String): String? {
        val patterns = listOf(
            Regex("(?is)file\\s*:\\s*[\\\"']([^\\\"']+\\.pl\\.txt(?:\\?[^\\\"']*)?)[\\\"']"),
            Regex("(?is)[\\\"']file[\\\"']\\s*:\\s*[\\\"']([^\\\"']+\\.pl\\.txt(?:\\?[^\\\"']*)?)[\\\"']"),
            Regex("(?is)(https?:\\/\\/[^\\s\\\"'<>]+\\.pl\\.txt(?:\\?[^\\s\\\"'<>]*)?)")
        )
        val raw = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) } ?: return null
        return resolveUrl(baseUrl, raw)
    }

    private fun extractKnigavuheBookId(html: String): String? {
        val patterns = listOf(
            Regex("/ajax/book_data/(\\d+)/"),
            Regex("/play/id/(\\d+)/?"),
            Regex("/covers/(\\d+)(?:[/._-]|$)"),
            Regex("/audio/(\\d+)/(?:mobile/)?"),
            Regex("(?i)data-book-id\\s*=\\s*[\\\"'](\\d+)[\\\"']"),
            Regex("(?i)[\\\"']book_id[\\\"']\\s*:\\s*[\\\"']?(\\d+)"),
            Regex("(?i)[\\\"']bookId[\\\"']\\s*:\\s*[\\\"']?(\\d+)"),
            Regex("(?i)book[_-]?id[\\\"']?\\s*[:=]\\s*[\\\"']?(\\d+)")
        )
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
    }

    private fun extractKnigavuheMergedPlaylist(html: String): JSONArray? {
        val markers = listOf("\"merged_playlist\"", "'merged_playlist'", "merged_playlist")
        for (marker in markers) {
            val raw = extractBalancedArrayAfter(html, marker) ?: continue
            val parsed = runCatching { JSONArray(raw) }.getOrNull() ?: continue
            if (parsed.length() > 0) return parsed
        }
        return null
    }

    private fun extractBalancedObjectAfter(text: String, marker: String): String? {
        val startMarker = text.indexOf(marker)
        if (startMarker < 0) return null
        val start = text.indexOf('{', startMarker + marker.length)
        if (start < 0) return null
        return extractBalanced(text, start, '{', '}')
    }

    private fun extractBalancedArrayAfter(text: String, marker: String): String? {
        val startMarker = text.indexOf(marker)
        if (startMarker < 0) return null
        val start = text.indexOf('[', startMarker + marker.length)
        if (start < 0) return null
        return extractBalanced(text, start, '[', ']')
    }

    private fun extractBalanced(text: String, start: Int, open: Char, close: Char): String? {
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (quote != null) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = null
                continue
            }
            if (c == '\"' || c == '\'') { quote = c; continue }
            if (c == open) depth++
            if (c == close) {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        return null
    }

    private fun extractJsonMedia(raw: String, baseUrl: String): List<String> {
        fun collect(value: Any?, out: MutableList<String>) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), out)
                is JSONObject -> {
                    val preferred = listOf("src", "url", "file", "path")
                    preferred.forEach { key ->
                        val v = value.optString(key).trim()
                        if (v.isNotBlank()) resolveUrl(baseUrl, v)?.let(out::add)
                    }
                    val keys = value.keys()
                    while (keys.hasNext()) collect(value.opt(keys.next()), out)
                }
                is String -> if (value.contains(".mp3", true) || value.contains(".m4a", true) ||
                    value.contains(".m4b", true) || value.contains(".aac", true) ||
                    value.contains(".ogg", true) || value.contains(".opus", true) ||
                    value.contains(".flac", true)) {
                    resolveUrl(baseUrl, value)?.let(out::add)
                }
            }
        }
        val out = mutableListOf<String>()
        val parsed: Any = runCatching { JSONArray(raw) }.getOrElse {
            runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        }
        collect(parsed, out)
        return out
    }

    private fun firstHttpString(obj: JSONObject, vararg keys: String): String? = keys.asSequence()
        .map { obj.optString(it).trim().replace("\\/", "/") }
        .firstOrNull { it.startsWith("http://") || it.startsWith("https://") }

    private fun resolveUrl(baseUrl: String, raw: String?): String? = runCatching {
        val value = raw?.trim()?.replace("\\/", "/").orEmpty()
        if (value.isBlank()) return@runCatching null
        URI(baseUrl).resolve(value).toString()
    }.getOrNull()

    private fun isHttpMedia(url: String): Boolean = runCatching {
        val uri = URI(url)
        val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        uri.scheme?.lowercase() in setOf("http", "https") && ext in setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac")
    }.getOrDefault(false)

    private fun hostAllowed(url: String, allowed: Set<String>): Boolean = runCatching {
        val host = URI(url).host?.lowercase().orEmpty()
        allowed.any { a -> host == a.lowercase() || host.endsWith(".${a.lowercase()}") }
    }.getOrDefault(false)
}
