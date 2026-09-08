package org.audoiboo.tracker.plugin

import android.content.Context
import android.util.Log
import org.audoiboo.tracker.AudoibooDatabase
import org.audoiboo.tracker.BookEntity
import org.audoiboo.tracker.LibraryDao
import org.audoiboo.tracker.SeriesWithBooks

/** Adapter for catalog-only Room series (`catalog://...`). */
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
        version = 7,
        hosts = setOf("catalog.local"),
        capabilities = setOf(SourceCapability.SERIES_LOOKUP)
    )

    override fun supports(url: String): Boolean = url.startsWith("catalog://", ignoreCase = true)

    override suspend fun resolveSeries(url: String): SourceSeries? {
        if (!supports(url)) return null
        val item = findSeries(url) ?: run {
            Log.w(TAG, "catalog resolve: MISS url=$url")
            return null
        }
        return SourceSeries(
            sourceId = descriptor.id,
            url = item.series.url,
            title = item.series.name,
            authors = item.books.mapNotNull { it.author }
                .flatMap(::splitAuthors)
                .distinct()
                .map(::SourceAuthor)
        )
    }

    override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> {
        require(series.sourceId == descriptor.id) { "Series belongs to another source" }
        val context = appContext ?: return emptyList()
        val dao = AudoibooDatabase.get(context).libraryDao()
        val storedItem = dao.library().firstOrNull { it.series.url == series.url } ?: return emptyList()
        val item = repairStoredBooks(dao, storedItem)

        val baseBooks = item.books.sortedBy { it.sortIndex }.map { it.toSourceBook(item.series.name) }
        val discoverable = PluginPackageRuntime.registry.plugins
            .filter { plugin ->
                plugin.descriptor.id != descriptor.id &&
                    (SourceCapability.SERIES_SEARCH in plugin.descriptor.capabilities ||
                        SourceCapability.SERIES_DISCOVERY in plugin.descriptor.capabilities)
            }
            .joinToString(",") { plugin -> plugin.descriptor.id }

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
        }

        val findings = allFindings.filter { it.disposition == MatchDisposition.AUTO_ACCEPT }
        if (findings.isEmpty()) return baseBooks

        val booksById = item.books.associateBy { it.id }.toMutableMap()
        val roomUpdates = linkedMapOf<String, BookEntity>()
        val now = System.currentTimeMillis()

        findings.forEach { finding ->
            // A canonical book may be present on many providers. Only de-duplicate within one
            // provider finding; never globally claim the book across providers.
            val claimedInFinding = linkedSetOf<String>()
            val memberBooks = SeriesBookMembershipPolicy.filter(finding.series, finding.books)
            Log.i(TAG, "catalog source=${finding.sourceId}: providerBooks=${finding.books.size} membershipAccepted=${memberBooks.size}")

            memberBooks.forEach remoteLoop@ { remote ->
                val rawMatch = SourceIdentityMatcher.bestBookMatch(
                    incoming = remote,
                    candidates = canonical.books.filterNot { it.id in claimedInFinding }
                )
                val match = rawMatch?.takeIf { it.disposition == MatchDisposition.AUTO_ACCEPT }
                if (match == null) {
                    val reason = rawMatch?.let { "${it.disposition}/${"%.3f".format(it.confidence)}" } ?: "NO_MATCH"
                    Log.w(TAG, "catalog book SKIP_UNMATCHED source=${finding.sourceId} remote='${remote.title}' reason=$reason url=${remote.url}")
                    return@remoteLoop
                }

                val existing = booksById[match.value.id] ?: return@remoteLoop
                claimedInFinding += existing.id

                // Keep one logical Room row. Discovery snapshots/UI retain the other provider URLs.
                // Hydrate a catalog-only row from the first good provider, but later providers must
                // never create another BookEntity for the same canonical work.
                if (supports(existing.url)) {
                    val hydrated = existing.copy(
                        url = remote.url,
                        author = sourceAuthor(remote) ?: existing.author,
                        coverUrl = remote.coverUrl ?: existing.coverUrl,
                        updatedAt = now
                    )
                    booksById[existing.id] = hydrated
                    roomUpdates[existing.id] = hydrated
                    Log.i(TAG, "catalog book HYDRATE source=${finding.sourceId} remote='${remote.title}' -> canonical='${existing.title}' confidence=${"%.3f".format(match.confidence)}")
                } else {
                    Log.i(TAG, "catalog book MERGE_SOURCE source=${finding.sourceId} remote='${remote.title}' -> canonical='${existing.title}' confidence=${"%.3f".format(match.confidence)}")
                }
            }
        }

        if (roomUpdates.isNotEmpty()) dao.upsertBooks(roomUpdates.values.toList())

        val finalBooks = dao.seriesWithBooks(item.series.id)?.books.orEmpty().sortedBy { it.sortIndex }
        Log.i(TAG, "catalog discovery END series=${item.series.name} finalRoomBooks=${finalBooks.size} promoted=0")
        return finalBooks.map { it.toSourceBook(item.series.name) }
    }

    /**
     * Repairs rows written by the older unsafe PROMOTE path. Synthetic promoted rows use an id of
     * `<seriesId>::http...`. They are never canonical works: if they match a real catalog row we
     * merge useful metadata into that row; otherwise we prune them. This removes both ordinary
     * provider duplicates and false positives such as a same-title book by another author.
     */
    private suspend fun repairStoredBooks(dao: LibraryDao, item: SeriesWithBooks): SeriesWithBooks {
        if (item.books.size < 2) return item

        val stable = item.books.filterNot { isSyntheticPromotion(it, item.series.id) }
        val synthetic = item.books.filter { isSyntheticPromotion(it, item.series.id) }
        val now = System.currentTimeMillis()

        val mergedStable = stable.associateBy { it.id }.toMutableMap()
        synthetic.forEach { extra ->
            val extraKey = CatalogSeriesHeuristics.logicalBookKey(extra.title, item.series.name)
            val matches = stable.filter { anchor ->
                CatalogSeriesHeuristics.logicalBookKey(anchor.title, item.series.name) == extraKey &&
                    authorsCompatible(anchor.author, extra.author)
            }
            val anchor = matches.minByOrNull { it.sortIndex }
            if (anchor != null) {
                val current = mergedStable.getValue(anchor.id)
                mergedStable[anchor.id] = current.copy(
                    url = if (supports(current.url) && !supports(extra.url)) extra.url else current.url,
                    author = current.author ?: extra.author,
                    coverUrl = current.coverUrl ?: extra.coverUrl,
                    status = if (current.status == "READ" || extra.status == "READ") "READ" else current.status,
                    archiveUrl = current.archiveUrl ?: extra.archiveUrl,
                    updatedAt = now
                )
                Log.i(TAG, "catalog Room MERGE_STALE series=${item.series.name} extra='${extra.title}' -> '${anchor.title}' author='${extra.author}'")
            } else {
                Log.w(TAG, "catalog Room PRUNE_STALE series=${item.series.name} title='${extra.title}' author='${extra.author}'")
            }
        }

        // Collapse duplicate canonical rows conservatively: same logical title plus compatible
        // author identity. This handles aliases like Лаэндэл / Алексей Лаэндэл /
        // Алексей Андриенко (Лаэндэл), but does not merge a чужий author with the same title.
        val buckets = mutableListOf<MutableList<BookEntity>>()
        mergedStable.values.sortedBy { it.sortIndex }.forEach { book ->
            val key = CatalogSeriesHeuristics.logicalBookKey(book.title, item.series.name)
            val bucket = buckets.firstOrNull { group ->
                val first = group.first()
                CatalogSeriesHeuristics.logicalBookKey(first.title, item.series.name) == key &&
                    authorsCompatible(first.author, book.author)
            }
            if (bucket == null) buckets += mutableListOf(book) else bucket += book
        }

        val winners = buckets.map { group -> mergeCanonicalGroup(group, now) }
            .sortedBy { it.sortIndex }
            .mapIndexed { index, book -> book.copy(sortIndex = index, updatedAt = now) }

        if (winners.size == item.books.size && synthetic.isEmpty()) return item

        dao.deleteMissingBooks(item.series.id, winners.map { it.id })
        dao.upsertBooks(winners)
        val message = "catalog Room REPAIR series=${item.series.name} before=${item.books.size} after=${winners.size} removed=${item.books.size - winners.size} synthetic=${synthetic.size}"
        Log.i(TAG, message)
        SeriesDiagnosticLog.i(message)
        return dao.seriesWithBooks(item.series.id) ?: item.copy(books = winners)
    }

    private fun mergeCanonicalGroup(group: List<BookEntity>, now: Long): BookEntity {
        val anchor = group.firstOrNull { supports(it.url) } ?: group.minBy { it.sortIndex }
        val richer = group.maxWithOrNull(
            compareBy<BookEntity> { if (!it.coverUrl.isNullOrBlank()) 4 else 0 }
                .thenBy { if (!it.author.isNullOrBlank()) 2 else 0 }
                .thenBy { if (!supports(it.url)) 1 else 0 }
        ) ?: anchor
        return anchor.copy(
            url = group.firstOrNull { !supports(it.url) }?.url ?: anchor.url,
            author = richer.author ?: anchor.author,
            coverUrl = richer.coverUrl ?: anchor.coverUrl,
            status = if (group.any { it.status == "READ" }) "READ" else anchor.status,
            archiveUrl = group.firstNotNullOfOrNull { it.archiveUrl },
            sortIndex = group.minOf { it.sortIndex },
            updatedAt = now
        )
    }

    private fun authorsCompatible(left: String?, right: String?): Boolean {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return false
        val a = SourceIdentityMatcher.normalizeAuthor(left)
        val b = SourceIdentityMatcher.normalizeAuthor(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b) return true

        val genericGivenNames = setOf(
            "алексей", "александр", "андрей", "дмитрий", "иван", "михаил", "николай",
            "роман", "сергей", "владимир", "евгений", "максим", "артем", "антон"
        )
        val common = a.split(' ').toSet().intersect(b.split(' ').toSet())
        return common.any { token -> token.length >= 4 && token !in genericGivenNames }
    }

    private fun isSyntheticPromotion(book: BookEntity, seriesId: String): Boolean =
        book.id.startsWith("$seriesId::http://") || book.id.startsWith("$seriesId::https://")

    private suspend fun findSeries(url: String): SeriesWithBooks? {
        val context = appContext ?: return null
        return AudoibooDatabase.get(context).libraryDao().library()
            .firstOrNull { it.series.url == url }
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

    private fun BookEntity.toSourceBook(seriesTitle: String) = SourceBook(
        sourceId = descriptor.id,
        url = url,
        title = title,
        authors = author?.let(::splitAuthors).orEmpty().map(::SourceAuthor),
        seriesTitle = seriesTitle,
        seriesNumber = (sortIndex + 1).toDouble(),
        coverUrl = coverUrl
    )

    private fun sourceAuthor(book: SourceBook): String? =
        book.authors.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }

    private fun splitAuthors(value: String): List<String> = value
        .split(',', ';', '&', '/')
        .map { it.trim() }
        .filter { it.isNotBlank() }
}
