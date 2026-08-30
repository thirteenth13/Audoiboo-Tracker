package org.audoiboo.tracker.plugin

import java.io.File
import java.net.URI

/**
 * SourcePlugin facade backed by one installed declarative package.
 * All parsing and network activity is delegated to the host-controlled runtime.
 */
class DeclarativeSourcePlugin(
    private val manifest: PluginPackageManifest,
    private val packageDir: File,
    private val runtime: DeclarativePluginRuntime
) : SourcePlugin, SeriesProvider, BookProvider, DownloadResolver {
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
        return runtime.resolveSeries(manifest, packageDir, url)
    }

    override suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook> {
        if (series.sourceId != manifest.id) return emptyList()
        val canLookup = SourceCapability.BOOK_LOOKUP in manifest.capabilities && manifest.entrypoints.containsKey("bookLookup")
        return series.books.mapNotNull { ref ->
            if (canLookup) {
                runtime.resolveBook(manifest, packageDir, ref.url)
            } else {
                SourceBook(
                    sourceId = manifest.id,
                    remoteId = ref.remoteId,
                    url = ref.url,
                    title = ref.title ?: return@mapNotNull null,
                    authors = series.authors,
                    seriesTitle = series.title,
                    seriesNumber = ref.number
                )
            }
        }
    }

    override suspend fun loadBook(url: String): SourceBook? {
        if (!supports(url)) return null
        return runtime.resolveBook(manifest, packageDir, url)
    }

    override suspend fun resolveDownloads(book: SourceBook): List<DownloadCandidate> {
        if (book.sourceId != manifest.id || !supports(book.url)) return emptyList()
        return runtime.resolveDownloads(manifest, packageDir, book.url)
    }
}
