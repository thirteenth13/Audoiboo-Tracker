package org.audoiboo.tracker.plugin

const val SOURCE_PLUGIN_API_VERSION = 1

enum class SourceCapability {
    BOOK_LOOKUP,
    SERIES_LOOKUP,
    BOOK_SEARCH,
    SERIES_SEARCH,
    SERIES_DISCOVERY,
    DOWNLOAD_RESOLUTION,
    STREAM_RESOLUTION,
    TORRENT_RESOLUTION
}

data class SourceDescriptor(
    val id: String,
    val name: String,
    val version: Int,
    val apiVersion: Int = SOURCE_PLUGIN_API_VERSION,
    val hosts: Set<String>,
    val capabilities: Set<SourceCapability>
) {
    init {
        require(id.isNotBlank()) { "Source id must not be blank" }
        require(name.isNotBlank()) { "Source name must not be blank" }
        require(version > 0) { "Source version must be positive" }
        require(apiVersion > 0) { "Source API version must be positive" }
        require(hosts.isNotEmpty()) { "Source must declare at least one host" }
    }
}

data class SourceAuthor(
    val name: String,
    val url: String? = null
)

data class SourceBookRef(
    val remoteId: String? = null,
    val url: String,
    val title: String? = null,
    val number: Double? = null
)

data class SourceSeries(
    val sourceId: String,
    val remoteId: String? = null,
    val url: String,
    val title: String,
    val description: String? = null,
    val authors: List<SourceAuthor> = emptyList(),
    val books: List<SourceBookRef> = emptyList()
)

data class SourceBook(
    val sourceId: String,
    val remoteId: String? = null,
    val url: String,
    val title: String,
    val authors: List<SourceAuthor> = emptyList(),
    val seriesTitle: String? = null,
    val seriesNumber: Double? = null,
    val coverUrl: String? = null,
    val description: String? = null
)

data class KnownBook(
    val title: String,
    val number: Double? = null,
    val authors: List<String> = emptyList()
)

data class SeriesSearchQuery(
    val title: String,
    val knownAuthors: List<String> = emptyList(),
    val knownBooks: List<KnownBook> = emptyList(),
    val alternativeTitles: List<String> = emptyList()
)

data class SeriesCandidate(
    val series: SourceSeries,
    val sourceScore: Float? = null
)

enum class DownloadType {
    DIRECT_FILE,
    ARCHIVE,
    STREAM,
    TORRENT,
    MAGNET
}

data class DownloadCandidate(
    val type: DownloadType,
    val url: String,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val quality: String? = null,
    val priority: Int = 0
)

interface SourcePlugin {
    val descriptor: SourceDescriptor

    fun supports(url: String): Boolean
}

interface SeriesProvider {
    suspend fun resolveSeries(url: String): SourceSeries?

    suspend fun loadSeriesBooks(series: SourceSeries): List<SourceBook>
}

interface BookProvider {
    suspend fun loadBook(url: String): SourceBook?
}

interface SeriesSearchProvider {
    suspend fun searchSeries(query: SeriesSearchQuery): List<SeriesCandidate>
}

interface DownloadResolver {
    suspend fun resolveDownloads(book: SourceBook): List<DownloadCandidate>
}

class SourcePluginRegistry(
    plugins: Collection<SourcePlugin>
) {
    private val pluginsById: Map<String, SourcePlugin> = plugins.associateBy { it.descriptor.id }.also { indexed ->
        require(indexed.size == plugins.size) { "Duplicate source plugin id" }
    }

    val plugins: List<SourcePlugin>
        get() = pluginsById.values.sortedBy { it.descriptor.name.lowercase() }

    fun byId(id: String): SourcePlugin? = pluginsById[id]

    fun forUrl(url: String): SourcePlugin? = plugins.firstOrNull { it.supports(url) }

    fun withCapability(capability: SourceCapability): List<SourcePlugin> =
        plugins.filter { capability in it.descriptor.capabilities }
}
