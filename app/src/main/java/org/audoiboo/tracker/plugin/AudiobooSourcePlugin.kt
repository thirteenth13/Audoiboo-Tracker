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
        val author = canonical.authors.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }

        val encoded = URLEncoder.encode(title, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val candidateUrl = "https://audioboo.org/xfsearch/cikl/$encoded/"
        diagnostic("provider audioboo DISCOVERY_DIRECT url=$candidateUrl")
        val direct = resolveSeries(candidateUrl)
        diagnostic("provider audioboo DISCOVERY_DIRECT result=${direct?.url ?: "null"} title=${direct?.title ?: "-"}")
        if (direct != null) {
            val directBooks = AudiobooFastParser.parseSeries(direct.url).orEmpty()
            val directRefs = canonicalRefs(directBooks, author.orEmpty(), canonical)
            diagnostic("provider audioboo DISCOVERY_DIRECT parsedBooks=${directBooks.size} canonicalRefs=${directRefs.size}")
            if (directRefs.isNotEmpty()) {
                return listOf(
                    SeriesCandidate(
                        direct.copy(
                            authors = author?.let { listOf(SourceAuthor(it)) }.orEmpty(),
                            books = directRefs
                        )
                    )
                )
            }
            diagnostic("provider audioboo DISCOVERY_DIRECT rejected=no-canonical-books; trying author/search fallback")
        }

        if (author == null) return emptyList()
        val encodedAuthor = URLEncoder.encode(author, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val authorUrl = "https://audioboo.org/xfsearch/avtora/$encodedAuthor/"
        diagnostic("provider audioboo DISCOVERY_AUTHOR url=$authorUrl")
        val parsedAuthorBooks = AudiobooFastParser.parseSeries(authorUrl).orEmpty()
        diagnostic("provider audioboo DISCOVERY_AUTHOR parsedBooks=${parsedAuthorBooks.size}")
        val authorRefs = canonicalRefs(parsedAuthorBooks, author, canonical)
        diagnostic("provider audioboo DISCOVERY_AUTHOR matchedRefs=${authorRefs.size}")

        if (authorRefs.isNotEmpty()) {
            return listOf(
                SeriesCandidate(
                    SourceSeries(
                        sourceId = descriptor.id,
                        url = authorUrl,
                        title = canonical.title,
                        authors = listOf(SourceAuthor(author, authorUrl)),
                        books = authorRefs
                    )
                )
            )
        }

        return searchFallback(author, canonical)
    }

    private fun canonicalRefs(
        books: List<org.audoiboo.tracker.FastBook>,
        fallbackAuthor: String,
        canonical: CanonicalSeriesMatchInput
    ): List<SourceBookRef> = books.mapNotNull { book ->
        val authors = book.author?.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }
            ?: fallbackAuthor.takeIf { it.isNotBlank() }?.let { listOf(SourceAuthor(it)) }.orEmpty()
        val sourceBook = SourceBook(
            sourceId = descriptor.id,
            url = book.url,
            title = book.title,
            authors = authors,
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

    private fun searchFallback(author: String, canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
        val firstBook = canonical.books.firstOrNull()?.title?.trim().orEmpty()
        val probeQuery = listOf(author, firstBook).filter { it.isNotBlank() }.joinToString(" ").ifBlank { canonical.title }
        val encoded = URLEncoder.encode(probeQuery, StandardCharsets.UTF_8.name())
        val routes = listOf(
            "index" to "https://audioboo.org/index.php?do=search&subaction=search&story=$encoded",
            "root" to "https://audioboo.org/?do=search&subaction=search&story=$encoded"
        )

        routes.forEach { (name, url) ->
            diagnostic("provider audioboo SEARCH_FALLBACK route=$name url=$url")
            val books = AudiobooFastParser.parseSeries(url).orEmpty()
            val refs = canonicalRefs(books, author, canonical)
            diagnostic("PROBE_SUMMARY id=audioboo route=$name parsedBooks=${books.size} canonicalRefs=${refs.size}")
            if (refs.isNotEmpty()) {
                return listOf(
                    SeriesCandidate(
                        SourceSeries(
                            sourceId = descriptor.id,
                            url = url,
                            title = canonical.title,
                            authors = listOf(SourceAuthor(author)),
                            books = refs
                        )
                    )
                )
            }
        }
        return emptyList()
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
