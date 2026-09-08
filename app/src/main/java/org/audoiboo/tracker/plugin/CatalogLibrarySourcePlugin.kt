package org.audoiboo.tracker.plugin

import android.content.Context
import android.util.Log
import org.audoiboo.tracker.AudoibooDatabase
import org.audoiboo.tracker.BookEntity
import org.audoiboo.tracker.LibraryDao
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
 * Genuinely missing remote volumes are inserted into the canonical series.
 */
object CatalogLibrarySourcePlugin : SourcePlugin, SeriesProvider {
    private const val ID = "catalog-library"
    private const val TAG = "AudoibooSeries"
    private const val DISCOVERY_PREFS = "source_discovery"
    private const val DISCOVERY_LAST_SUCCESS_PREFIX = "last_success:"

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    override val descriptor = SourceDescriptor(
        id = ID,
        name = "Catalog library",
        version = 5,
        hosts = setOf("catalog.local"),
        capabilities = setOf(SourceCapability.SERIES_LOOKUP)
    )

    override fun supports(url: String): Boolean = url.startsWith("catalog://", ignoreCase = true)

    override suspend fun resolveSeries(url: String): SourceSeries? {
        if (!supports(url)) return null
        val item = findSeries(url)
        if (item == null) {
            Log.w(TAG, "catalog resolve: MISS url=$url")
            return null
        }
        Log.i(TAG, "catalog resolve: title=${item.series.name} id=${item.series.id} books=${item.books.size} url=$url")
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
        val context = appContext
        if (context == null) {
            Log.e(TAG, "catalog load: no appContext title=${series.title}")
            return emptyList()
        }
        val db = AudoibooDatabase.get(context)
        val dao = db.libraryDao()
        val storedItem = dao.library().firstOrNull { it.series.url == series.url }
        if (storedItem == null) {
            Log.e(TAG, "catalog load: Room series not found title=${series.title} url=${series.url}")
            return emptyList()
        }

        // A refresh must also repair data written by older builds. Previously, a provider match
        // could be inserted as a second Room row instead of replacing the catalog:// row. Those
        // stale duplicates then became part of the next canonical input (for example 10 -> 16
        // books) and poisoned book matching. Collapse logical duplicates before discovery, keep the
        // stable original row id where possible, and merge the real provider URL/metadata into it.
        val item = dedupeStoredBooks(dao, storedItem)

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

        val discoverable = PluginPackageRuntime.registry.plugins
            .filter { plugin ->
                plugin.descriptor.id != descriptor.id &&
                    (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities ||
                        SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities)
            }
            .joinToString(",") { plugin ->
                val caps = buildList {
                    if (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities) add("SEARCH")
                    if (SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities) add("DISCOVERY")
                }.joinToString("+")
                "${plugin.descriptor.id}[$caps]"
            }
        Log.i(TAG, "catalog discovery START series=${item.series.name} canonicalBooks=${baseBooks.size} providers=$discoverable")
        SeriesDiagnosticLog.i("catalog discovery START series=${item.series.name} canonicalBooks=${baseBooks.size} providers=$discoverable")

        val canonical = canonicalInput(item)
        val discoveryResult = runCatching {
            SourceDiscoveryEngine(PluginPackageRuntime.registry)
                .discoverSeries(canonical, excludeSourceId = descriptor.id)
        }.onFailure { error ->
            Log.e(TAG, "catalog discovery FAILED series=${item.series.name}: ${error.javaClass.simpleName}: ${error.message}", error)
        }
        val allFindings = discoveryResult.getOrDefault(emptyList())

        if (discoveryResult.isSuccess) {
            context.getSharedPreferences(DISCOVERY_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("$DISCOVERY_LAST_SUCCESS_PREFIX${item.series.id}", System.currentTimeMillis())
                .apply()
            Log.i(TAG, "catalog discovery MARK_SUCCESS series=${item.series.name} id=${item.series.id}; duplicate Room discovery suppressed")
        }

        allFindings.forEach { finding ->
            Log.i(
                TAG,
                "catalog finding source=${finding.sourceId} disposition=${finding.disposition} confidence=${"%.3f".format(finding.confidence)} books=${finding.books.size} title=${finding.series.title} url=${finding.series.url} evidence=${finding.evidence.joinToString(" | ")}"
            )
        }
        val findings = allFindings.filter { it.disposition == MatchDisposition.AUTO_ACCEPT }
        if (findings.isEmpty()) {
            Log.w(TAG, "catalog discovery END series=${item.series.name}: acceptedFindings=0 rawFindings=${allFindings.size}; no Room changes")
            return baseBooks
        }

        val candidates = canonical.books.toMutableList()
        val booksById = item.books.associateBy { it.id }.toMutableMap()
        val claimedExistingIds = linkedSetOf<String>()
        val promoted = mutableListOf<SourceBook>()
        val roomUpdates = mutableListOf<BookEntity>()
        var syntheticIndex = 0
        var nextSortIndex = (item.books.maxOfOrNull { it.sortIndex } ?: -1) + 1
        val now = System.currentTimeMillis()

        findings.forEach findingLoop@ { finding ->
            val memberBooks = SeriesBookMembershipPolicy.filter(finding.series, finding.books)
            Log.i(TAG, "catalog source=${finding.sourceId}: providerBooks=${finding.books.size} membershipAccepted=${memberBooks.size}")
            memberBooks.forEach remoteLoop@ { remote ->
                val rawMatch = SourceIdentityMatcher.bestBookMatch(
                    incoming = remote,
                    candidates = candidates.filterNot { it.id in claimedExistingIds }
                )
                val existingMatch = rawMatch?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }

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
                            Log.i(
                                TAG,
                                "catalog book HYDRATE source=${finding.sourceId} remote='${remote.title}' -> canonical='${existing.title}' confidence=${"%.3f".format(existingMatch.confidence)} oldUrl=${existing.url} newUrl=${remote.url} cover=${!remote.coverUrl.isNullOrBlank()}"
                            )
                        } else {
                            Log.i(
                                TAG,
                                "catalog book KEEP_REAL source=${finding.sourceId} remote='${remote.title}' -> canonical='${existing.title}' confidence=${"%.3f".format(existingMatch.confidence)} existingUrl=${existing.url}"
                            )
                        }
                    } else {
                        Log.w(TAG, "catalog book MATCH_ID_MISSING source=${finding.sourceId} remote='${remote.title}' matchedId=${existingMatch.value.id}")
                    }
                    return@remoteLoop
                }

                if (rawMatch != null) {
                    Log.w(
                        TAG,
                        "catalog book NOT_AUTO source=${finding.sourceId} remote='${remote.title}' best='${rawMatch.value.title}' disposition=${rawMatch.disposition} confidence=${"%.3f".format(rawMatch.confidence)} evidence=${rawMatch.evidence.joinToString(" | ")}"
                    )
                } else {
                    Log.w(TAG, "catalog book NO_MATCH source=${finding.sourceId} remote='${remote.title}' number=${remote.seriesNumber} url=${remote.url}")
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
                Log.i(TAG, "catalog book PROMOTE source=${finding.sourceId} title='${remote.title}' number=${remote.seriesNumber} sortIndex=${newEntity.sortIndex} url=${remote.url}")

                candidates += CanonicalBookMatchInput(
                    id = "promoted:${syntheticIndex++}",
                    title = promotedBook.title,
                    authors = promotedBook.authors.map { it.name },
                    number = promotedBook.seriesNumber
                )
            }
        }

        if (roomUpdates.isNotEmpty()) {
            val originalIds = item.books.map { it.id }.toSet()
            dao.upsertBooks(roomUpdates)
            Log.i(TAG, "catalog Room UPSERT series=${item.series.name} updates=${roomUpdates.size} hydrated=${roomUpdates.count { it.id in originalIds }} promoted=${promoted.size}")
        } else {
            Log.w(TAG, "catalog Room UPSERT series=${item.series.name}: updates=0 despite accepted findings=${findings.size}")
        }

        val finalBooks = dao.seriesWithBooks(item.series.id)?.books.orEmpty().sortedBy { it.sortIndex }
        finalBooks.forEachIndexed { index, book ->
            val source = PluginPackageRuntime.registry.forUrl(book.url, SourceCapability.BOOK_LOOKUP)?.descriptor?.id
                ?: if (supports(book.url)) descriptor.id else "unknown"
            Log.i(TAG, "catalog final ${index + 1}/${finalBooks.size} source=$source title='${book.title}' url=${book.url} cover=${!book.coverUrl.isNullOrBlank()}")
        }
        Log.i(TAG, "catalog discovery END series=${item.series.name} finalRoomBooks=${finalBooks.size} returned=${baseBooks.size + promoted.size}")

        return (baseBooks + promoted)
            .distinctBy { book -> SourceIdentityMatcher.normalizeTitle(book.title) }
    }

    /**
     * Repairs duplicate Room rows created by older refresh logic. Logical identity is the normalized
     * title inside one canonical series. We keep a stable catalog row id when available (so tags and
     * reading state remain attached), but merge a real provider URL and richer metadata from any
     * duplicate into that row. The duplicate rows are deleted before the merged winners are upserted
     * to avoid the unique books.url constraint.
     */
    private suspend fun dedupeStoredBooks(dao: LibraryDao, item: SeriesWithBooks): SeriesWithBooks {
        if (item.books.size < 2) return item

        val groups = item.books
            .sortedBy { it.sortIndex }
            .groupBy { SourceIdentityMatcher.normalizeTitle(it.title) }
        if (groups.size == item.books.size) return item

        val now = System.currentTimeMillis()
        val winners = groups.values.map { group ->
            val anchor = group.firstOrNull { supports(it.url) } ?: group.minBy { it.sortIndex }
            val real = group.firstOrNull { !supports(it.url) }
            val read = group.any { it.status == "READ" }
            anchor.copy(
                url = real?.url ?: anchor.url,
                author = real?.author?.takeIf { it.isNotBlank() }
                    ?: group.firstNotNullOfOrNull { it.author?.takeIf(String::isNotBlank) },
                coverUrl = real?.coverUrl?.takeIf { it.isNotBlank() }
                    ?: group.firstNotNullOfOrNull { it.coverUrl?.takeIf(String::isNotBlank) },
                status = if (read) "READ" else anchor.status,
                archiveUrl = group.firstNotNullOfOrNull { it.archiveUrl?.takeIf(String::isNotBlank) },
                sortIndex = group.minOf { it.sortIndex },
                updatedAt = now
            )
        }.sortedBy { it.sortIndex }
            .mapIndexed { index, book -> book.copy(sortIndex = index, updatedAt = now) }

        val removed = item.books.size - winners.size
        dao.deleteMissingBooks(item.series.id, winners.map { it.id })
        dao.upsertBooks(winners)
        val message = "catalog Room DEDUPE series=${item.series.name} before=${item.books.size} after=${winners.size} removed=$removed"
        Log.i(TAG, message)
        SeriesDiagnosticLog.i(message)

        return dao.seriesWithBooks(item.series.id) ?: item.copy(books = winners)
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
