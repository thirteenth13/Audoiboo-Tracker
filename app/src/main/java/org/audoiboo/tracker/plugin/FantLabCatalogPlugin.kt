package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Russian-language bibliographic catalog backed by FantLab's public JSON API. */
object FantLabCatalogPlugin : SourcePlugin, AuthorCatalogProvider, CatalogBookSearchProvider, SeriesDiscoveryProvider {
    override val descriptor = SourceDescriptor(
        id = "fantlab",
        name = "FantLab Catalog",
        version = 5,
        hosts = setOf("api.fantlab.ru", "fantlab.ru", "www.fantlab.ru"),
        capabilities = setOf(SourceCapability.AUTHOR_CATALOG, SourceCapability.BOOK_SEARCH, SourceCapability.SERIES_DISCOVERY)
    )

    override fun supports(url: String): Boolean = runCatching {
        URI(url).host?.lowercase()?.trimEnd('.') in descriptor.hosts
    }.getOrDefault(false)

    /**
     * Lets ordinary provider-backed series participate in catalog federation too. SourceDiscovery
     * already has the canonical title, authors and known books, so resolve the author's FantLab
     * bibliography and expose matching catalog cycles as direct candidates. The normal identity
     * matcher remains the authority: these candidates are only AUTO_ACCEPTed when their book set
     * overlaps the canonical series strongly enough.
     */
    override suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
        val authorQueries = (canonical.authors + canonical.books.flatMap { it.authors })
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(SourceIdentityMatcher::normalizeAuthor)
            .take(3)
        if (authorQueries.isEmpty()) return emptyList()

        val candidates = linkedMapOf<String, SeriesCandidate>()
        for (authorQuery in authorQueries) {
            val authors = searchAuthors(authorQuery, 2)
                .filter { it.confidence >= 0.78f }
            for (author in authors) {
                val grouped = CatalogSeriesHeuristics.group(loadAuthorCatalog(author, 300))
                grouped.series.forEach { catalogSeries ->
                    val sourceSeries = SourceSeries(
                        sourceId = descriptor.id,
                        remoteId = "${author.remoteId}:${SourceIdentityMatcher.normalizeTitle(catalogSeries.title)}",
                        url = "https://fantlab.ru/autor${author.remoteId}#series-${encode(catalogSeries.title)}",
                        title = catalogSeries.title,
                        authors = catalogSeries.authors.ifEmpty { listOf(author.name) }
                            .distinctBy(SourceIdentityMatcher::normalizeAuthor)
                            .map(::SourceAuthor),
                        books = catalogSeries.books.map { book ->
                            SourceBookRef(
                                remoteId = book.remoteId,
                                url = "https://fantlab.ru/work${book.remoteId}",
                                title = book.title,
                                number = book.seriesNumber
                            )
                        }
                    )
                    val seriesMatch = SourceIdentityMatcher.bestSeriesMatch(
                        incoming = sourceSeries,
                        incomingBooks = sourceSeries.books.mapNotNull { ref ->
                            val title = ref.title ?: return@mapNotNull null
                            SourceBook(
                                sourceId = descriptor.id,
                                remoteId = ref.remoteId,
                                url = ref.url,
                                title = title,
                                authors = sourceSeries.authors,
                                seriesTitle = sourceSeries.title,
                                seriesNumber = ref.number
                            )
                        },
                        candidates = listOf(canonical)
                    ) ?: return@forEach
                    if (seriesMatch.disposition == MatchDisposition.REJECT) return@forEach
                    val key = SourceIdentityMatcher.normalizeTitle(catalogSeries.title)
                    val candidate = SeriesCandidate(sourceSeries, seriesMatch.confidence)
                    val previous = candidates[key]
                    if (previous == null || (candidate.sourceScore ?: 0f) > (previous.sourceScore ?: 0f)) {
                        candidates[key] = candidate
                    }
                }
            }
        }
        return candidates.values.sortedByDescending { it.sourceScore ?: 0f }.take(5)
    }

    override suspend fun searchBooks(query: String, limit: Int): List<CatalogBookSearchHit> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val response = get("https://api.fantlab.ru/search-works?q=${encode(clean)}&page=1&onlymatches=1", 4L * 1024 * 1024)
            ?: return emptyList()
        val array = parseSearchArray(response) ?: return emptyList()
        val safeLimit = limit.coerceIn(1, 25)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = flexibleInt(item, "work_id") ?: continue
                val russianTitle = item.optString("rusname").trim()
                val originalTitle = item.optString("name").trim()
                val title = russianTitle.ifBlank { originalTitle }
                val confidence = listOf(russianTitle, originalTitle, item.optString("fullname").trim())
                    .filter(String::isNotBlank)
                    .maxOfOrNull { catalogTitleConfidence(clean, it) } ?: 0f
                if (id <= 0 || title.isBlank() || confidence < 0.60f) continue
                val authors = splitAuthors(
                    item.optString("all_autor_rusname").ifBlank { item.optString("all_autor_name") }
                ).ifEmpty {
                    (1..5).mapNotNull { index -> item.optString("autor${index}_rusname").trim().takeIf(String::isNotBlank) }
                }
                val inferred = CatalogSeriesHeuristics.infer(title)
                add(
                    CatalogBookSearchHit(
                        CatalogBook(
                            providerId = descriptor.id,
                            remoteId = id.toString(),
                            title = title,
                            authors = authors,
                            seriesTitles = inferred?.let { listOf(it.title) }.orEmpty(),
                            seriesNumber = inferred?.number,
                            firstPublishYear = flexibleInt(item, "year")?.takeIf { it > 0 }
                        ),
                        confidence
                    )
                )
            }
        }.sortedByDescending { it.confidence }.take(safeLimit)
    }

    override suspend fun searchAuthors(query: String, limit: Int): List<CatalogAuthor> {
        val expected = normalize(query)
        if (expected.isBlank()) return emptyList()
        val response = get("https://api.fantlab.ru/search-autors?q=${encode(query.trim())}&page=1&onlymatches=1", 2L * 1024 * 1024)
            ?: return emptyList()
        val array = parseSearchArray(response) ?: return emptyList()
        return parseAuthorSearch(expected, array).take(limit.coerceIn(1, 10))
    }

    override suspend fun loadAuthorCatalog(author: CatalogAuthor, limit: Int): AuthorCatalog {
        require(author.providerId == descriptor.id) { "Author belongs to another catalog provider" }
        val authorId = author.remoteId.toIntOrNull() ?: return AuthorCatalog(author, emptyList())
        val safeLimit = limit.coerceIn(1, 500)

        val primary = loadAuthorJson("https://api.fantlab.ru/autor/$authorId?biblio_blocks=1&sort=year")
        val primaryBooks = primary?.let { parseCatalog(author, it) }.orEmpty()
        if (primaryBooks.isNotEmpty()) return AuthorCatalog(author, primaryBooks.take(safeLimit))

        // FantLab's API is still pre-1.0 and has changed response shapes before. The documented
        // /extended endpoint exposes the same biblio_blocks, so use it as a compatibility fallback.
        val extended = loadAuthorJson("https://api.fantlab.ru/autor/$authorId/extended?biblio_blocks=1&sort=year")
        return AuthorCatalog(author, extended?.let { parseCatalog(author, it) }.orEmpty().take(safeLimit))
    }

    internal fun parseSearchArray(response: String): JSONArray? {
        runCatching { JSONArray(response) }.getOrNull()?.let { return it }
        return runCatching { JSONObject(response) }.getOrNull()?.optJSONArray("matches")
    }

    internal fun parseAuthorSearch(expected: String, array: JSONArray): List<CatalogAuthor> = buildList {
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = flexibleInt(item, "autor_id") ?: continue
            if (id <= 0) continue
            val russianName = item.optString("rusname").trim()
            val originalName = item.optString("name").trim()
            val displayName = russianName.ifBlank { originalName }
            if (displayName.isBlank()) continue
            val pseudoNames = item.optString("pseudo_names").split(',', ';', '/').map(String::trim).filter(String::isNotBlank)
            val alternatives = (listOf(originalName, russianName) + pseudoNames).filter(String::isNotBlank).distinct().filterNot { it == displayName }
            val confidence = (listOf(displayName) + alternatives).maxOfOrNull { authorConfidence(expected, normalize(it)) } ?: 0f
            if (confidence < 0.55f) continue
            add(CatalogAuthor(descriptor.id, id.toString(), displayName, alternatives, flexibleInt(item, "workcount"), confidence))
        }
    }.sortedWith(compareByDescending<CatalogAuthor> { it.confidence }.thenBy { normalize(it.name) })

    internal fun parseCatalog(author: CatalogAuthor, json: JSONObject): List<CatalogBook> {
        val books = linkedMapOf<String, CatalogBook>()
        parseCycles(author, json.optJSONObject("cycles_blocks"), books)
        parseStandalone(author, json.optJSONObject("works_blocks"), books)
        return books.values.sortedWith(
            compareBy<CatalogBook> { it.seriesTitles.firstOrNull()?.let(SourceIdentityMatcher::normalizeTitle).orEmpty() }
                .thenBy { it.seriesNumber ?: Double.MAX_VALUE }
                .thenBy { it.firstPublishYear ?: Int.MAX_VALUE }
                .thenBy { SourceIdentityMatcher.normalizeTitle(it.title) }
        )
    }

    private fun parseCycles(author: CatalogAuthor, blocks: JSONObject?, books: MutableMap<String, CatalogBook>) {
        if (blocks == null) return
        val blockKeys = blocks.keys()
        while (blockKeys.hasNext()) {
            val block = blocks.optJSONObject(blockKeys.next()) ?: continue
            val cycles = block.optJSONArray("list") ?: continue
            for (cycleIndex in 0 until cycles.length()) {
                val cycle = cycles.optJSONObject(cycleIndex) ?: continue
                val seriesTitle = firstNonBlank(cycle, "work_name", "work_name_orig") ?: continue
                val children = cycle.optJSONArray("children") ?: continue
                for (bookIndex in 0 until children.length()) {
                    val child = children.optJSONObject(bookIndex) ?: continue
                    val title = firstNonBlank(child, "work_name", "work_name_orig") ?: continue
                    val inferredNumber = CatalogSeriesHeuristics.infer(title)?.number
                    val book = parseWork(author, child, seriesTitle, inferredNumber ?: (bookIndex + 1).toDouble()) ?: continue
                    books.putIfAbsent(book.remoteId, book)
                }
            }
        }
    }

    private fun parseStandalone(author: CatalogAuthor, blocks: JSONObject?, books: MutableMap<String, CatalogBook>) {
        if (blocks == null) return
        val blockKeys = blocks.keys()
        while (blockKeys.hasNext()) {
            val block = blocks.optJSONObject(blockKeys.next()) ?: continue
            val works = block.optJSONArray("list") ?: continue
            for (i in 0 until works.length()) {
                val work = works.optJSONObject(i) ?: continue
                val book = parseWork(author, work, null, null) ?: continue
                books.putIfAbsent(book.remoteId, book)
            }
        }
    }

    private fun parseWork(author: CatalogAuthor, work: JSONObject, seriesTitle: String?, seriesNumber: Double?): CatalogBook? {
        val id = flexibleInt(work, "work_id") ?: return null
        val title = firstNonBlank(work, "work_name", "work_name_orig") ?: return null
        if (id <= 0) return null
        val authors = work.optJSONArray("authors")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.optString("name")?.trim()?.takeIf(String::isNotBlank) }
        }.orEmpty().ifEmpty { listOf(author.name) }
        return CatalogBook(
            providerId = descriptor.id,
            remoteId = id.toString(),
            title = title,
            authors = authors,
            seriesTitles = seriesTitle?.let(::listOf).orEmpty(),
            seriesNumber = seriesNumber,
            firstPublishYear = flexibleInt(work, "work_year")?.takeIf { it > 0 }
        )
    }

    private fun splitAuthors(value: String): List<String> = value
        .replace(Regex("<[^>]+>"), " ")
        .split(',', ';', '/')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()

    private suspend fun loadAuthorJson(url: String): JSONObject? =
        get(url, 10L * 1024 * 1024)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private suspend fun get(url: String, maxBytes: Long): String? {
        val response = HostPluginHttpTransport.get(PluginHttpRequest(url, mapOf("Accept" to "application/json")), maxBytes)
        if (response.statusCode !in 200..299) return null
        return response.body
    }

    private fun firstNonBlank(json: JSONObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf(String::isNotBlank) }

    private fun flexibleInt(json: JSONObject, key: String): Int? {
        val value = json.opt(key) ?: return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun authorConfidence(expected: String, candidate: String): Float = when {
        candidate == expected -> 1f
        candidate.contains(expected) || expected.contains(candidate) -> 0.88f
        expected.split(' ').filter(String::isNotBlank).toSet().let { tokens -> tokens.isNotEmpty() && tokens.all { it in candidate } } -> 0.78f
        else -> 0f
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun normalize(value: String): String = SourceIdentityMatcher.normalizeTitle(value)
}
