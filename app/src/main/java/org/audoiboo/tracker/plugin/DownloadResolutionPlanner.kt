package org.audoiboo.tracker.plugin

import kotlinx.coroutines.CancellationException

data class ResolvedDownloadCandidate(
    val book: SourceBook,
    val candidate: DownloadCandidate
)

/**
 * Resolves a canonical book through all known source observations and chooses the most complete
 * downloadable payload. A broken or currently unavailable source is isolated so other mapped
 * sources can still provide the book.
 */
class DownloadResolutionPlanner(
    private val registry: SourcePluginRegistry
) {
    private companion object {
        /**
         * These providers normally expose a playlist rather than one standalone file. A single
         * direct-file hit is useful, but is treated as weak because stale DOM selectors and
         * partially-loaded players have repeatedly produced exactly one track of a larger book.
         */
        val PLAYLIST_PROVIDERS = setOf("baza-knig", "lis10book", "knigavuhe")
    }

    private data class SourceResolution(
        val resolved: List<ResolvedDownloadCandidate>,
        val sourceOrder: Int
    )

    /**
     * Resolves every mapped source, then returns the strongest payload rather than the first one
     * that happens to work. A whole-book archive wins over direct-file playlists; between
     * playlists, the source exposing more distinct tracks wins. Equal-quality results preserve
     * mapped source order for deterministic behaviour.
     */
    suspend fun resolveAll(sources: List<SourceBook>): List<ResolvedDownloadCandidate> {
        val options = mutableListOf<SourceResolution>()

        sources
            .distinctBy { it.sourceId to SourceKeys.normalizeUrl(it.url) }
            .forEachIndexed { sourceOrder, book ->
                // Device WebView capture intentionally returns all media requests produced by the
                // player. Keep them together because they are usually consecutive audiobook tracks.
                if (DeviceWebViewResolutionRuntime.supports(book.url)) {
                    val deviceCandidates = try {
                        DeviceWebViewResolutionRuntime.resolve(book)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        emptyList()
                    }
                    if (deviceCandidates.isNotEmpty()) {
                        options += SourceResolution(
                            resolved = deviceCandidates.map { ResolvedDownloadCandidate(book, it) },
                            sourceOrder = sourceOrder
                        )
                    }
                }

                val plugin = registry.byId(book.sourceId) ?: return@forEachIndexed
                if (SourceCapability.DOWNLOAD_RESOLUTION !in plugin.descriptor.capabilities) return@forEachIndexed
                if (!plugin.supports(book.url)) return@forEachIndexed
                val resolver = plugin as? DownloadResolver ?: return@forEachIndexed
                val candidates = try {
                    resolver.resolveDownloads(book)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    emptyList()
                }
                val selected = DownloadCandidateSelectionPolicy.preferredWithinSource(candidates)
                if (selected.isNotEmpty()) {
                    options += SourceResolution(
                        resolved = selected.map { ResolvedDownloadCandidate(book, it) },
                        sourceOrder = sourceOrder
                    )
                }
            }

        return options.maxWithOrNull(
            compareBy<SourceResolution> { qualityTier(it.resolved) }
                .thenBy { directTrackCount(it.resolved) }
                .thenBy { maxPriority(it.resolved) }
                .thenBy { -it.sourceOrder }
        )?.resolved.orEmpty()
    }

    private fun qualityTier(resolved: List<ResolvedDownloadCandidate>): Int {
        if (resolved.any { it.candidate.type == DownloadType.ARCHIVE }) return 3
        if (resolved.size > 1) return 2
        val single = resolved.singleOrNull() ?: return -1
        return if (isWeakPlaylistResult(single.book, listOf(single.candidate))) 0 else 1
    }

    private fun directTrackCount(resolved: List<ResolvedDownloadCandidate>): Int =
        resolved.count { it.candidate.type == DownloadType.DIRECT_FILE }

    private fun maxPriority(resolved: List<ResolvedDownloadCandidate>): Int =
        resolved.maxOfOrNull { it.candidate.priority } ?: Int.MIN_VALUE

    private fun isWeakPlaylistResult(book: SourceBook, candidates: List<DownloadCandidate>): Boolean =
        book.sourceId in PLAYLIST_PROVIDERS &&
            candidates.size == 1 &&
            candidates.single().type == DownloadType.DIRECT_FILE

    /** Backwards-compatible single-payload API for callers that genuinely need one candidate. */
    suspend fun resolve(sources: List<SourceBook>): ResolvedDownloadCandidate? =
        resolveAll(sources).firstOrNull()
}
