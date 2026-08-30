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
    /**
     * Returns every downloadable part from the first source that can resolve the book.
     * This is important for sites where one book is exposed as many MP3/M4A tracks.
     */
    suspend fun resolveAll(sources: List<SourceBook>): List<ResolvedDownloadCandidate> {
        sources
            .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.url) }
            .forEach { book ->
                // Device WebView capture intentionally returns all media requests produced by the
                // player. Do not collapse that list to the highest-priority item: those candidates
                // are usually consecutive tracks of the same audiobook.
                if (DeviceWebViewResolutionRuntime.supports(book.url)) {
                    val deviceCandidates = try {
                        DeviceWebViewResolutionRuntime.resolve(book)
                            .distinctBy { SourceKeys.normalizeUrl(it.url) }
                            .sortedByDescending { it.priority }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        emptyList()
                    }
                    if (deviceCandidates.isNotEmpty()) {
                        return deviceCandidates.map { ResolvedDownloadCandidate(book, it) }
                    }
                }

                val plugin = registry.byId(book.sourceId) ?: return@forEach
                if (SourceCapability.DOWNLOAD_RESOLUTION !in plugin.descriptor.capabilities) return@forEach
                if (!plugin.supports(book.url)) return@forEach
                val resolver = plugin as? DownloadResolver ?: return@forEach
                val candidates = try {
                    resolver.resolveDownloads(book)
                        .filter { it.type == DownloadType.ARCHIVE || it.type == DownloadType.DIRECT_FILE }
                        .distinctBy { SourceKeys.normalizeUrl(it.url) }
                        .sortedWith(
                            compareByDescending<DownloadCandidate> { it.priority }
                                .thenByDescending { it.type == DownloadType.ARCHIVE }
                        )
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    emptyList()
                }
                if (candidates.isNotEmpty()) {
                    // An archive represents the whole book and should stay a single job. When the
                    // winning payload is a direct file, keep all direct files from this source.
                    val winner = candidates.first()
                    val selected = if (winner.type == DownloadType.ARCHIVE) {
                        listOf(winner)
                    } else {
                        candidates.filter { it.type == DownloadType.DIRECT_FILE }
                    }
                    return selected.map { ResolvedDownloadCandidate(book, it) }
                }
            }
        return emptyList()
    }

    /** Backwards-compatible single-payload API for callers that genuinely need one candidate. */
    suspend fun resolve(sources: List<SourceBook>): ResolvedDownloadCandidate? =
        resolveAll(sources).firstOrNull()
}
