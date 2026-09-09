package org.audoiboo.tracker

import android.content.Context
import org.audoiboo.tracker.plugin.HostPluginHttpTransport
import org.audoiboo.tracker.plugin.PluginHttpRequest
import org.audoiboo.tracker.plugin.SourceMetadataRepository
import java.net.URI

/** Best-effort recovery for Baza-Knig covers that live beside the playlist/tracks on redirectto.cc. */
internal object BazaCoverRecovery {
    private const val SOURCE_ID = "baza-knig"
    private const val MAX_PAGE_BYTES = 4L * 1024L * 1024L
    private const val MAX_COVER_BYTES = 3L * 1024L * 1024L

    suspend fun repairMissing(context: Context, limit: Int = 12) {
        if (limit <= 0) return
        val dao = AudoibooDatabase.get(context).libraryDao()
        val library = dao.library()
        var remaining = limit

        for (item in library) {
            if (remaining <= 0) break
            for (book in item.books) {
                if (remaining <= 0) break
                if (!book.coverUrl.isNullOrBlank()) continue
                val source = SourceMetadataRepository.sourcesForBook(context, book.id)
                    .firstOrNull { it.sourceId == SOURCE_ID } ?: continue
                remaining--

                val cover = resolve(source.url) ?: continue
                dao.upsertBooks(listOf(book.copy(coverUrl = cover, updatedAt = System.currentTimeMillis())))
            }
        }
    }

    internal fun candidateFrom(pageUrl: String, html: String): String? {
        directCoverInHtml(html, pageUrl)?.let { return it }
        val playlistUrl = playlistUrl(html, pageUrl) ?: return null
        val directory = runCatching { URI(playlistUrl).resolve(".") }.getOrNull() ?: return null
        val slug = Regex("(?i)/audio-\\d+-([^/?#]+)")
            .find(URI(pageUrl).path.orEmpty())
            ?.groupValues?.getOrNull(1)
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return directory.resolve("$slug.jpg").toString()
    }

    private suspend fun resolve(pageUrl: String): String? {
        val page = runCatching {
            HostPluginHttpTransport.get(PluginHttpRequest(pageUrl), MAX_PAGE_BYTES)
        }.getOrNull() ?: return null
        if (page.statusCode !in 200..299) return null

        val candidate = candidateFrom(page.finalUrl, page.body) ?: return null
        if (!isTrustedCover(candidate)) return null
        val probe = runCatching {
            HostPluginHttpTransport.get(PluginHttpRequest(candidate), MAX_COVER_BYTES)
        }.getOrNull() ?: return null
        return candidate.takeIf { probe.statusCode in 200..299 }
    }

    private fun directCoverInHtml(html: String, baseUrl: String): String? {
        val patterns = listOf(
            Regex("(?is)(https?:\\/\\/redirectto\\.cc/s01/[^\\s\\\"'<>]+\\.(?:jpe?g|png|webp)(?:\\?[^\\s\\\"'<>]*)?)"),
            Regex("(?is)(//redirectto\\.cc/s01/[^\\s\\\"'<>]+\\.(?:jpe?g|png|webp)(?:\\?[^\\s\\\"'<>]*)?)")
        )
        val raw = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) } ?: return null
        return runCatching { URI(baseUrl).resolve(raw.replace("\\/", "/")).toString() }.getOrNull()
    }

    private fun playlistUrl(html: String, baseUrl: String): String? {
        val patterns = listOf(
            Regex("(?is)file\\s*:\\s*[\\\"']([^\\\"']+\\.pl\\.txt(?:\\?[^\\\"']*)?)[\\\"']"),
            Regex("(?is)[\\\"']file[\\\"']\\s*:\\s*[\\\"']([^\\\"']+\\.pl\\.txt(?:\\?[^\\\"']*)?)[\\\"']"),
            Regex("(?is)(https?:\\/\\/[^\\s\\\"'<>]+\\.pl\\.txt(?:\\?[^\\s\\\"'<>]*)?)")
        )
        val raw = patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) } ?: return null
        return runCatching { URI(baseUrl).resolve(raw.replace("\\/", "/")).toString() }.getOrNull()
    }

    private fun isTrustedCover(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase()
        uri.scheme.equals("https", true) && host == "redirectto.cc" && ext in setOf("jpg", "jpeg", "png", "webp")
    }.getOrDefault(false)
}
