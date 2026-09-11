package org.audoiboo.tracker.plugin

import android.util.Log
import org.audoiboo.tracker.AudiobooFastParser
import org.audoiboo.tracker.AudiobooSearchParser
import org.audoiboo.tracker.FastBook
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
            diagnostic("provider audioboo DISCOVERY_DIRECT rejected=no-canonical-books; trying author fallback")
        }

        if (author == null) return emptyList()
        val authorFallback = authorPageFallback(author, canonical)
        if (authorFallback.isNotEmpty()) return authorFallback
        return searchFallback(author, canonical)
    }

    private fun canonicalRefs(
        books: List<FastBook>,
        fallbackAuthor: String,
        canonical: CanonicalSeriesMatchInput,
        trustFallbackAuthor: Boolean = false
    ): List<SourceBookRef> = books.mapNotNull { book ->
        val authors = when {
            trustFallbackAuthor && fallbackAuthor.isNotBlank() -> listOf(SourceAuthor(fallbackAuthor))
            !book.author.isNullOrBlank() -> listOf(SourceAuthor(book.author))
            fallbackAuthor.isNotBlank() -> listOf(SourceAuthor(fallbackAuthor))
            else -> emptyList()
        }
        val sourceBook = SourceBook(
            sourceId = descriptor.id,
            url = book.url,
            title = book.title,
            authors = authors,
            seriesTitle = book.seriesTitle,
            coverUrl = book.coverUrl
        )
        val strictMatch = SourceIdentityMatcher.bestBookMatch(sourceBook, canonical.books)
            ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
        val match = strictMatch?.value
            ?: if (trustFallbackAuthor) audiobooAuthorPageBookMatch(book, canonical.books) else null
            ?: return@mapNotNull null
        SourceBookRef(
            url = book.url,
            title = book.title,
            number = match.number
        )
    }.distinctBy { SourceKeys.normalizeUrl(it.url) }

    private fun authorPageFallback(author: String, canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
        audiobooAuthorAliases(author).forEach { alias ->
            val encoded = URLEncoder.encode(alias, StandardCharsets.UTF_8.name()).replace("+", "%20")
            val refs = linkedMapOf<String, SourceBookRef>()
            var firstUrl: String? = null

            for (page in 1..3) {
                val url = "https://audioboo.org/xfsearch/avtora/$encoded/" + if (page == 1) "" else "page/$page/"
                firstUrl = firstUrl ?: url
                diagnostic("provider audioboo AUTHOR_FALLBACK alias='$alias' page=$page url=$url")
                val books = AudiobooFastParser.parseSeries(url).orEmpty()
                val pageRefs = canonicalRefs(books, author, canonical, trustFallbackAuthor = true)
                val sample = books.take(4).joinToString(" | ") { book ->
                    "${book.title} [series=${book.seriesTitle ?: "-"}; author=${book.author ?: "-"}]"
                }
                diagnostic("provider audioboo AUTHOR_FALLBACK alias='$alias' page=$page parsedBooks=${books.size} canonicalRefs=${pageRefs.size} sample=$sample")
                pageRefs.forEach { ref -> refs.putIfAbsent(SourceKeys.normalizeUrl(ref.url), ref) }

                // Audioboo returns 404 after the final author page. parseSeries intentionally turns
                // that into an empty result, so one empty page after a successful page is terminal.
                if (books.isEmpty()) break
            }

            if (refs.isNotEmpty()) {
                return listOf(
                    SeriesCandidate(
                        SourceSeries(
                            sourceId = descriptor.id,
                            url = firstUrl ?: return@forEach,
                            title = canonical.title,
                            authors = listOf(SourceAuthor(author)),
                            books = refs.values.sortedWith(
                                compareBy<SourceBookRef> { it.number ?: Double.MAX_VALUE }
                                    .thenBy { it.title.orEmpty() }
                            )
                        )
                    )
                )
            }
        }
        return emptyList()
    }

    private fun searchFallback(author: String, canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
        val firstBook = canonical.books.firstOrNull()?.title.orEmpty()
        val queries = audiobooFallbackQueries(author, firstBook)

        queries.forEach { query ->
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            val routes = listOf(
                "index" to "https://audioboo.org/index.php?do=search&subaction=search&story=$encoded",
                "root" to "https://audioboo.org/?do=search&subaction=search&story=$encoded"
            )

            routes.forEach { (name, url) ->
                diagnostic("provider audioboo SEARCH_FALLBACK query='$query' route=$name url=$url")
                val books = AudiobooSearchParser.fetch(url)
                val refs = canonicalRefs(books, author, canonical)
                diagnostic("PROBE_SUMMARY id=audioboo query='$query' route=$name parsedBooks=${books.size} canonicalRefs=${refs.size}")
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

internal fun audiobooFallbackQueries(author: String, firstBook: String): List<String> {
    val cleanAuthor = author.trim()
    val cleanBook = firstBook.trim()
    val authorAndBook = listOf(cleanAuthor, cleanBook).filter(String::isNotBlank).joinToString(" ")
    return listOf(authorAndBook, cleanAuthor)
        .filter(String::isNotBlank)
        .distinctBy(SourceIdentityMatcher::normalizeTitle)
}

internal fun audiobooAuthorAliases(author: String): List<String> {
    val tokens = author.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.isEmpty()) return emptyList()
    val reversed = tokens.reversed().joinToString(" ")
    return listOf(tokens.joinToString(" "), reversed)
        .filter(String::isNotBlank)
        .distinctBy(SourceIdentityMatcher::normalizeTitle)
}

internal fun audiobooAuthorPageBookMatch(
    book: FastBook,
    candidates: List<CanonicalBookMatchInput>
): CanonicalBookMatchInput? {
    if (candidates.isEmpty()) return null
    val provider = audiobooComparableBookTitle(book.title, book.seriesTitle)
    if (provider.isBlank()) return null

    val exact = candidates.filter { candidate ->
        audiobooComparableBookTitle(candidate.title, book.seriesTitle) == provider
    }
    if (exact.size == 1) return exact.single()

    // Author-page provenance already establishes the author. Permit a provider decoration such as
    // "Древний 1. Катастрофа" only when exactly one canonical title is a strong suffix/containment
    // match. Single-token titles are deliberately excluded from this relaxed path to avoid linking
    // broad titles such as "Тьма" to "Рассвет Тьмы".
    val relaxed = candidates.filter { candidate ->
        val canonical = audiobooComparableBookTitle(candidate.title, book.seriesTitle)
        val tokens = canonical.split(' ').filter(String::isNotBlank)
        tokens.size >= 2 && canonical.length >= 8 &&
            (provider.endsWith(" $canonical") || provider.startsWith("$canonical "))
    }
    return relaxed.singleOrNull()
}

internal fun audiobooComparableBookTitle(title: String, seriesTitle: String?): String {
    var normalized = SourceIdentityMatcher.normalizeTitle(title)
    val series = SourceIdentityMatcher.normalizeTitle(seriesTitle.orEmpty())
    if (series.isNotBlank()) {
        val seriesTokens = series.split(' ').filter { it.length > 1 }.toSet()
        normalized = normalized.split(' ')
            .filterNot { it in seriesTokens }
            .joinToString(" ")
    }
    return normalized
        .replace(Regex("\\b(книга|том|часть|частина|аудиокнига|аудиокниги|аудио|слушать|онлайн|book|volume|vol)\\b"), " ")
        .replace(Regex("\\b\\d+(?:[.,]\\d+)?\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

object BuiltInSourcePlugins {
    val registry: SourcePluginRegistry
        get() = BuiltInSourcePluginManager.registry
}
