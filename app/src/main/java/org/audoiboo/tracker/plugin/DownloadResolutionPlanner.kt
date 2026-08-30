package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException

data class ResolvedDownloadCandidate(
    val book: SourceBook,
    val candidate: DownloadCandidate
)

/**
 * Resolves a canonical book through its known source observations in order.
 * A broken or currently unavailable source is isolated so the next mapped source
 * can still provide a downloadable payload.
 */
class DownloadResolutionPlanner(
    private val registry: SourcePluginRegistry
) {
    suspend fun resolve(sources: List<SourceBook>): ResolvedDownloadCandidate? {
        sources
            .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.url) }
            .forEach { book ->
                // Knigavuhe can return a trial-only player to datacenter/headless clients while
                // exposing the real public playlist to the user's Android browser. Resolve it on
                // device before asking source plugins to fall back to HTTP-only extraction.
                if (DeviceWebViewResolutionRuntime.supports(book.url)) {
                    val deviceCandidate = try {
                        DeviceWebViewResolutionRuntime.resolve(book)
                            .maxByOrNull { it.priority }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        null
                    }
                    if (deviceCandidate != null) return ResolvedDownloadCandidate(book, deviceCandidate)
                }

                val plugin = registry.byId(book.sourceId) ?: return@forEach
                if (SourceCapability.DOWNLOAD_RESOLUTION !in plugin.descriptor.capabilities) return@forEach
                if (!plugin.supports(book.url)) return@forEach
                val resolver = plugin as? DownloadResolver ?: return@forEach
                val candidate = try {
                    resolver.resolveDownloads(book)
                        .filter { it.type == DownloadType.ARCHIVE || it.type == DownloadType.DIRECT_FILE }
                        .maxWithOrNull(
                            compareBy<DownloadCandidate> { it.priority }
                                .thenBy { it.type == DownloadType.ARCHIVE }
                        )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    null
                }
                if (candidate != null) return ResolvedDownloadCandidate(book, candidate)
            }
        return null
    }
}
