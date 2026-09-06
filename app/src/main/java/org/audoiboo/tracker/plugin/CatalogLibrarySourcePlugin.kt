package org.audoiboo.tracker.plugin

import android.content.Context
import org.audoiboo.tracker.AudoibooDatabase
import org.audoiboo.tracker.SeriesWithBooks

/**
 * Adapter for catalog-only Room series (`catalog://...`).
 *
 * Catalog imports are canonical metadata, not an audio website, so the normal URL based refresh
 * used to stop before source discovery. This adapter exposes the stored catalog series as a
 * SERIES_LOOKUP source and, while hydrating it, asks every enabled discovery/search provider for
 * audio candidates. Books that are already in the catalog stay canonical; genuinely missing
 * volumes are promoted into the primary result so RoomSeriesSync can add them in the same refresh.
 * The normal alternate-source pass then records the real provider links.
 */
object CatalogLibrarySourcePlugin : SourcePlugin, SeriesProvider {
    private const val ID = "catalog-library"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override val descriptor = SourceDescriptor(
        id = ID,
        name = "Catalog library",
        version = 1,
        hosts = setOf("catalog.local"),
        capabilities = setOf(SourceCapability.SERIES_LOOKUP)
    )

    override fun supports(url: String): Boolean = url.startsWith("catalog://", ignoreCase = true)

    override suspend fun resolveSeries(url: String): SourceSeries? {
        if (!supports(url)) return null
        val item = findSeries(url) ?: return null
        return SourceSeries(
            sourceId = descriptor.id,
            url = item.series.url,
            title = item.series.name,
            authors = item.books
                .mapNotNull { it.author }
                .flatMap(::splitAuthors)
                .distinct()
                .map(::SourceAuthor)
        )
    }

    override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> {
        require(series.sourceId == descriptor.id) { "Series belongs to another source" }
        val item = findSeries(series.url) ?: return emptyList()

        val baseBooks = item.books.sortedBy { it.sortIndex }.map { book ->
            SourceBook(
                sourceId = descriptor.id,
                url = book.url,
                title = book.title,
                authors = book.author?.let(::splitAuthors).orEmpty().map(::SourceAuthor),
                seriesTitle = item.series.name,
                seriesNumber = (book.sortIndex + 1).toDouble(),
                coverUrl = book.coverUrl
            )
        }

        val canonical = canonicalInput(item)
        val findings = SourceDiscoveryEngine(PluginPackageRuntime.registry)
            .discoverSeries(canonical, excludeSourceId = descriptor.id)
            .filter { it.disposition == MatchDisposition.AUTO_ACCEPT }

        if (findings.isEmpty()) return baseBooks

        val candidates = canonical.books.toMutableList()
        val promoted = mutableListOf<SourceBook>()
        var syntheticIndex = 0

        findings.forEach { finding ->
            SeriesBookMembershipPolicy.filter(finding.series, finding.books).forEach { remote ->
                val existing = SourceIdentityMatcher.bestBookMatch(remote, candidates)
                    ?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                if (existing != null) return@forEach

                val promotedBook = remote.copy(
                    sourceId = descriptor.id,
                    seriesTitle = item.series.name
                )
                promoted += promotedBook
                candidates += CanonicalBookMatchInput(
                    id = "promoted:${syntheticIndex++}",
                    title = promotedBook.title,
                    authors = promotedBook.authors.map { it.name },
                    number = promotedBook.seriesNumber
                )
            }
        }

        return (baseBooks + promoted)
            .distinctBy { book ->
                val title = SourceIdentityMatcher.normalizeTitle(book.title)
                val number = book.seriesNumber?.toString().orEmpty()
                "$title|$number"
            }
    }

    private suspend fun findSeries(url: String): SeriesWithBooks? {
        val context = appContext ?: return null
        return AudoibooDatabase.get(context).libraryDao().library()
            .firstOrNull { it.series.url.equals(url, ignoreCase = false) }
    }

    private fun canonicalInput(item: SeriesWithBooks) = CanonicalSeriesMatchInput(
        id = item.series.id,
        title = item.series.name,
        authors = item.books.mapNotNull { it.author }.flatMap(::splitAuthors).distinct(),
        books = item.books.sortedBy { it.sortIndex }.map { book ->
            CanonicalBookMatchInput(
                id = book.id,
                title = book.title,
                authors = book.author?.let(::splitAuthors).orEmpty(),
                number = (book.sortIndex + 1).toDouble()
            )
        }
    )

    private fun splitAuthors(value: String): List<String> = value
        .split(',', ';', '&')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
