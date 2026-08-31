package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException
import java.io.File
import java.net.URI

/**
 * SourcePlugin facade backed by one installed declarative package.
 * All parsing and network activity is delegated to the host-controlled runtime.
 */
class DeclarativeSourcePlugin(
    private val manifest: PluginPackageManifest,
    private val packageDir: File,
    private val runtime: DeclarativePluginRuntime,
    private val onRuntimeSuccess: (String, Int) -> Unit = { _, _ -> },
    private val onRuntimeFailure: (String, Int, Throwable) -> Unit = { _, _, _ -> }
) : SourcePlugin, SeriesProvider, BookProvider, SeriesSearchProvider, SeriesDiscoveryProvider, DownloadResolver {
    override val descriptor: SourceDescriptor = SourceDescriptor(
        id = manifest.id,
        name = manifest.name,
        version = manifest.version,
        apiVersion = manifest.apiVersion,
        hosts = manifest.hosts,
        capabilities = manifest.capabilities
    )

    override fun supports(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.trimEnd('.') }.getOrNull() ?: return false
        return host in manifest.hosts
    }

    override suspend fun resolveSeries(url: String): SourceSeries? {
        if (!supports(url)) return null
        return guarded {
            if (manifest.id != "izib") {
                runtime.resolveSeries(manifest, packageDir, url)
            } else {
                val primary = tryRuntime { runtime.resolveSeries(manifest, packageDir, url) }
                if (primary?.books?.isNotEmpty() == true) {
                    primary
                } else {
                    val alternate = alternateIzibUrl(url)
                        ?.let { alternateUrl -> tryRuntime { runtime.resolveSeries(manifest, packageDir, alternateUrl) } }
                    alternate?.takeIf { it.books.isNotEmpty() } ?: alternate ?: primary
                }
            }
        }
    }

    override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> {
        if (series.sourceId != manifest.id) return emptyList()
        val canLookup = SourceCapability.BOOK_LOOKUP in manifest.capabilities && manifest.entrypoints.containsKey("bookLookup")
        return guarded {
            val direct = series.books
                .filterNot { isGenericCatalogLabel(it.title) }
                .mapNotNull { ref ->
                    val fallback = ref.title?.trim()?.takeIf { it.isNotBlank() }?.let { title ->
                        SourceBook(
                            sourceId = manifest.id,
                            remoteId = ref.remoteId,
                            url = ref.url,
                            title = title,
                            authors = series.authors,
                            seriesTitle = series.title,
                            seriesNumber = ref.number
                        )
                    }
                    if (!canLookup) return@mapNotNull fallback

                    val resolved = tryRuntime {
                        if (manifest.id == "izib") loadIzibBookWithFallback(ref.url)
                        else runtime.resolveBook(manifest, packageDir, ref.url)
                    } ?: return@mapNotNull fallback

                    mergeResolvedWithFallback(resolved, fallback, series, ref)
                }

            // Some catalog pages lag behind the site's search index. When a declarative source can
            // both search and resolve books, use matching search hits as a conservative completeness
            // pass. Besides the plain series title, ask explicitly for the next likely volume.
            val canSearchForMissing = canLookup &&
                SourceCapability.SERIES_SEARCH in manifest.capabilities &&
                manifest.entrypoints.containsKey("seriesSearch")
            val supplemental = if (canSearchForMissing) {
                val expected = SourceIdentityMatcher.normalizeTitle(series.title)
                seriesCompletionQueries(series.title, direct)
                    .asSequence()
                    .flatMap { query ->
                        runtime.searchSeries(manifest, packageDir, SeriesSearchQuery(query)).asSequence()
                    }
                    .mapNotNull { candidate ->
                        tryRuntime {
                            if (manifest.id == "izib") loadIzibBookWithFallback(candidate.series.url)
                            else runtime.resolveBook(manifest, packageDir, candidate.series.url)
                        }
                    }
                    .filter { book ->
                        book.seriesTitle?.let(SourceIdentityMatcher::normalizeTitle) == expected
                    }
                    .distinctBy { SourceKeys.normalizeUrl(it.url) }
                    .toList()
            } else emptyList()

            (direct + supplemental)
                .distinctBy { SourceKeys.normalizeUrl(it.url) }
        }
    }

    override suspend fun loadBook(url: String): SourceBook? {
        if (!supports(url)) return null
        return guarded {
            if (manifest.id == "izib") loadIzibBookWithFallback(url)
            else runtime.resolveBook(manifest, packageDir, url)
        }
    }

    override suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate> {
        if (SourceCapability.SERIES_SEARCH !in manifest.capabilities) return emptyList()
        return guarded { runtime.searchSeries(manifest, packageDir, query) }
    }

    override suspend fun discoverSeries(canonical: CanonicalSeriesMatchInput): List<SeriesCandidate> {
        if (SourceCapability.SERIES_DISCOVERY !in manifest.capabilities) return emptyList()
        // Izib exposes a stable authors directory and author pages, but no verified public generic
        // search endpoint. The runtime therefore performs a bounded author-directory lookup and
        // returns only series links whose visible title matches the canonical series.
        if (manifest.id != "izib") return emptyList()
        return guarded { runtime.discoverIzibSeries(manifest, canonical) }
    }

    override suspend fun resolveDownloads(book: SourceBook): List<DownloadCandidate> {
        if (book.sourceId != manifest.id || !supports(book.url)) return emptyList()
        return guarded {
            PluginDownloadPolicy.filter(
                manifest,
                runtime.resolveDownloads(manifest, packageDir, book.url)
            )
        }
    }

    private fun loadIzibBookWithFallback(url: String): SourceBook? {
        val primary = tryRuntime { runtime.resolveBook(manifest, packageDir, url) }
        if (primary != null && !isGenericCatalogLabel(primary.title)) return primary
        val alternate = alternateIzibUrl(url)
            ?.let { alternateUrl -> tryRuntime { runtime.resolveBook(manifest, packageDir, alternateUrl) } }
        return alternate ?: primary
    }

    private fun mergeResolvedWithFallback(
        resolved: SourceBook,
        fallback: SourceBook?,
        series: SourceSeries,
        ref: SourceBookRef
    ): SourceBook {
        val title = if (isGenericCatalogLabel(resolved.title)) fallback?.title ?: resolved.title else resolved.title
        return resolved.copy(
            remoteId = resolved.remoteId ?: ref.remoteId,
            title = title,
            authors = resolved.authors.ifEmpty { fallback?.authors ?: series.authors },
            seriesTitle = resolved.seriesTitle
                ?.takeIf { it.isNotBlank() && !isGenericCatalogLabel(it) }
                ?: fallback?.seriesTitle
                ?: series.title,
            seriesNumber = resolved.seriesNumber ?: fallback?.seriesNumber ?: ref.number
        )
    }

    private inline fun <T> tryRuntime(block: () -> T): T? {
        return try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            null
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T {
        return try {
            block().also { onRuntimeSuccess(manifest.id, manifest.version) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onRuntimeFailure(manifest.id, manifest.version, t)
            throw t
        }
    }

    private fun isGenericCatalogLabel(title: String?): Boolean {
        val normalized = title?.let(SourceIdentityMatcher::normalizeTitle).orEmpty()
        return normalized in setOf(
            "аудиокниги",
            "аудиокниги слушать онлайн",
            "слушать аудиокниги онлайн",
            "audio books",
            "audiobooks"
        )
    }
}

internal fun alternateIzibUrl(url: String): String? = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase().orEmpty()
    val path = uri.rawPath?.takeIf { it.isNotBlank() } ?: "/"
    val rawQuery = uri.rawQuery.orEmpty()
    when (host) {
        "pda.izib.uk" -> {
            val query = buildList {
                if (rawQuery.isNotBlank()) add(rawQuery)
                if (!rawQuery.split('&').any { it.substringBefore('=').equals("keepversion", true) }) add("keepversion=1")
            }.joinToString("&")
            "https://izib.uk$path${query.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()}"
        }
        "izib.uk" -> {
            val query = rawQuery.split('&')
                .filter { it.isNotBlank() && !it.substringBefore('=').equals("keepversion", true) }
                .joinToString("&")
            "https://pda.izib.uk$path${query.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()}"
        }
        else -> null
    }
}.getOrNull()

internal fun seriesCompletionQueries(title: String, directBooks: List<SourceBook>): List<String> {
    val explicitMax = directBooks.mapNotNull { it.seriesNumber?.toInt() }.maxOrNull()
    val inferredMax = directBooks.mapNotNull { book ->
        Regex("(?<!\\d)(\\d{1,3})(?!\\d)")
            .findAll(book.title)
            .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
            .lastOrNull()
    }.maxOrNull()
    val currentMax = listOfNotNull(explicitMax, inferredMax, directBooks.size.takeIf { it > 0 }).maxOrNull()

    return buildList {
        add(title)
        currentMax?.let { add("$title ${it + 1}") }
    }.distinct()
}
