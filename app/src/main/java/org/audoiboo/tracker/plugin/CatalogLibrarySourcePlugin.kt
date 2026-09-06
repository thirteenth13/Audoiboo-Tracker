package org.audoiboo.tracker.plugin

import android.content.Context
import org.audoiboo.tracker.AudoibooDatabase
import org.audoiboo.tracker.BookEntity
import org.audoiboo.tracker.SeriesWithBooks

/**
 * Adapter for catalog-only Room series (`catalog://...`).
 *
 * Catalog imports are canonical metadata, not an audio website, so the normal URL based refresh
 * used to stop before source discovery. This adapter exposes the stored catalog series as a
 * SERIES_LOOKUP source and, while hydrating it, asks every enabled discovery/search provider for
 * audio candidates.
 *
 * When an audio provider matches an existing catalog book, the Room row is promoted from its
 * metadata-only catalog URL to the real provider URL (and cover/author metadata are hydrated).
 * Genuinely missing remote volumes are inserted into the canonical series. This makes a refresh
 * visibly useful instead of reporting the unchanged catalog book count while only persisting
 * hidden alternate-source snapshots.
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
        version = 2,
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
        val context = appContext ?: return emptyList()
        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        val item = dao.library().firstOrNull { it.series.url == series.url } ?: return emptyList()

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
        val booksById = item.books.associateBy { it.id }.toMutableMap()
        val claimedExistingIds = linkedSetOf<String>()
        val promoted = mutableListOf<SourceBook>()
        val roomUpdates = mutableListOf<BookEntity>()
        var syntheticIndex = 0
        var nextSortIndex = (item.books.maxOfOrNull { it.sortIndex } ?: -1) + 1
        val now = System.currentTimeMillis()

        findings.forEach findingLoop@ { finding ->
            SeriesBookMembershipPolicy.filter(finding.series, finding.books).forEach remoteLoop@ { remote ->
                val existingMatch = SourceIdentityMatcher.bestBookMatch(
                    incoming = remote,
                    candidates = candidates.filterNot { it.id in claimedExistingIds }
                )?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }

                if (existingMatch != null) {
                    val existing = booksById[existingMatch.value.id]
                    if (existing != null) {
                        claimedExistingIds += existing.id
                        if (supports(existing.url)) {
                            val hydrated = existing.copy(
                                url = remote.url,
                                author = sourceAuthor(remote) ?: existing.author,
                                coverUrl = remote.coverUrl ?: existing.coverUrl,
                                updatedAt = now
                            )
                            booksById[existing.id] = hydrated
                            roomUpdates += hydrated
                        }
                    }
                    return@remoteLoop
                }

                val promotedBook = remote.copy(
                    sourceId = descriptor.id,
                    seriesTitle = item.series.name
                )
                promoted += promotedBook

                val numberIndex = remote.seriesNumber
                    ?.takeIf { it >= 1.0 }
                    ?.toInt()
                    ?.minus(1)
                    ?.coerceAtLeast(0)
                val newId = "${item.series.id}::${remote.url}"
                val newEntity = BookEntity(
                    id = newId,
                    seriesId = item.series.id,
                    title = remote.title,
                    url = remote.url,
                    author = sourceAuthor(remote),
                    coverUrl = remote.coverUrl,
                    status = "NEW",
                    archiveUrl = null,
                    sortIndex = numberIndex ?: nextSortIndex++,
                    updatedAt = now
                )
                booksById[newId] = newEntity
                roomUpdates += newEntity

                candidates += CanonicalBookMatchInput(
                    id = "promoted:${syntheticIndex++}",
                    title = promotedBook.title,
                    authors = promotedBook.authors.map { it.name },
                    number = promotedBook.seriesNumber
                )
            }
        }

        if (roomUpdates.isNotEmpty()) {
            dao.upsertBooks(roomUpdates)
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

    private fun sourceAuthor(book: SourceBook): String? =
        book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }

    private fun splitAuthors(value: String): List<String> = value
        .split(',', ';', '&')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
