package org.audoiboo.tracker.plugin

import android.util.Log
import org.audoiboo.tracker.AudiobooFastParser
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AudiobooSourcePlugin : SourcePlugin, SeriesProvider, SeriesDiscoveryProvider, DownloadResolver {
    override val descriptor = SourceDescriptor(
        id = "audioboo",
        name = "Audioboo",
        version = 2,
        hosts = setOf("audioboo.org", "www.audioboo.org"),
        capabilities = setOf(
            SourceCapability.SERIES_LOOKUP,
            SourceCapability.SERIES_DISCOVERY,
            SourceCapability.DOWNLOAD_RESOLUTION
        )
    )

    private fun diagnostic(message: String) {
        Log.i("AudoibooSeries", message)
        SeriesDiagnosticLog.i(message)
    }

    override fun supports(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return host in descriptor.hosts
    }

    override suspend fun resolveSeries(url: String): SourceSeries? {
        if (!supports(url)) return null
        val resolved = AudiobooFastParser.resolveSeries(url) ?: return null
        return SourceSeries(
            sourceId = descriptor.id,
            url = resolved.url,
            title = resolved.name
        )
    }

    override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> {
        require(series.sourceId == descriptor.id) { "Series belongs to another source" }
        if (!supports(series.url)) return emptyList()
        return AudiobooFastParser.parseSeries(series.url).orEmpty().map { book ->
            SourceBook(
                sourceId = descriptor.id,
                url = book.url,
                title = book.title,
                authors = book.author?.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }.orEmpty(),
                seriesTitle = book.seriesTitle ?: series.title,
                coverUrl = book.coverUrl
            )
        }
    }

    override suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
        val title = canonical.title.trim()
        if (title.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(title, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val candidateUrl = "https://audioboo.org/xfsearch/cikl/$encoded/"
        diagnostic("provider audioboo DISCOVERY_DIRECT url=$candidateUrl")
        val direct = resolveSeries(candidateUrl)
        diagnostic("provider audioboo DISCOVERY_DIRECT result=${direct?.url ?: "null"} title=${direct?.title ?: "-"}")
        if (direct != null) return listOf(SeriesCandidate(direct))

        val author = canonical.authors.firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val encodedAuthor = URLEncoder.encode(author, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val authorUrl = "https://audioboo.org/xfsearch/avtora/$encodedAuthor/"
        diagnostic("provider audioboo DISCOVERY_AUTHOR url=$authorUrl")
        val parsedAuthorBooks = AudiobooFastParser.parseSeries(authorUrl).orEmpty()
        diagnostic("provider audioboo DISCOVERY_AUTHOR parsedBooks=${parsedAuthorBooks.size}")
        val refs = parsedAuthorBooks.mapNotNull { book ->
            val sourceBook = SourceBook(
                sourceId = descriptor.id,
                url = book.url,
                title = book.title,
                authors = book.author?.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }
                    ?: listOf(SourceAuthor(author)),
                seriesTitle = book.seriesTitle,
                coverUrl = book.coverUrl
            )
            val match = SourceIdentityMatcher.bestBookMatch(sourceBook, canonical.books)
                ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                ?: return@mapNotNull null
            SourceBookRef(
                url = book.url,
                title = book.title,
                number = match.value.number
            )
        }.distinctBy { SourceKeys.normalizeUrl(it.url) }
        diagnostic("provider audioboo DISCOVERY_AUTHOR matchedRefs=${refs.size}")

        if (refs.isEmpty()) {
            probeSearchRoutes(author, canonical)
            return emptyList()
        }
        return listOf(
            SeriesCandidate(
                SourceSeries(
                    sourceId = descriptor.id,
                    url = authorUrl,
                    title = canonical.title,
                    authors = listOf(SourceAuthor(author, authorUrl)),
                    books = refs
                )
            )
        )
    }

    private suspend fun probeSearchRoutes(author: String, canonical: CanonicalSeriesMatchInput) {
        val firstBook = canonical.books.firstOrNull()?.title?.trim().orEmpty()
        val probeQuery = listOf(author, firstBook).filter { it.isNotBlank() }.joinToString(" ").ifBlank { canonical.title }
        val encoded = URLEncoder.encode(probeQuery, StandardCharsets.UTF_8.name())
        val routes = listOf(
            "index" to "https://audioboo.org/index.php?do=search&subaction=search&story=$encoded",
            "root" to "https://audioboo.org/?do=search&subaction=search&story=$encoded"
        )
        routes.forEach { (name, url) ->
            diagnostic("PROBE audioboo SEARCH_ROUTE route=$name url=$url")
            val resolved = runCatching { resolveSeries(url) }.getOrNull()
            diagnostic(
                "PROBE_SUMMARY id=audioboo route=$name resolved=${resolved != null} " +
                    "series='${resolved?.title ?: "-"}' url=${resolved?.url ?: "-"}"
            )
        }
    }

    override suspend fun resolveDownloads(book: SourceBook): List<DownloadCandidate> {
        require(book.sourceId == descriptor.id) { "Book belongs to another source" }
        if (!supports(book.url)) return emptyList()
        val archiveUrl = AudiobooFastParser.findArchive(book.url) ?: return emptyList()
        return listOf(
            DownloadCandidate(
                type = DownloadType.ARCHIVE,
                url = archiveUrl,
                priority = 100
            )
        )
    }
}

object BuiltInSourcePlugins {
    val registry: SourcePluginRegistry
        get() = BuiltInSourcePluginManager.registry
}
