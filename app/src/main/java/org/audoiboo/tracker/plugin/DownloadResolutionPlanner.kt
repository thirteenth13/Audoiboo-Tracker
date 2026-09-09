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
    private companion object {
        /**
         * These providers normally expose a playlist rather than one standalone file. A single
         * direct-file hit is therefore useful, but not strong enough to stop source fallback:
         * stale DOM selectors and partially-loaded players have repeatedly produced exactly one
         * track while another mapped source can resolve the complete book.
         */
        val PLAYLIST_PROVIDERS = setOf("baza-knig", "lis10book", "knigavuhe")
    }

    /**
     * Returns every downloadable part from the first source that can resolve the book strongly.
     * A one-track result from a known playlist provider is kept as a provisional fallback while
     * the remaining mapped sources are checked. If none resolves better, the provisional result
     * is returned so legitimate one-track books still work.
     */
    suspend fun resolveAll(sources: List<SourceBook>): List<ResolvedDownloadCandidate> {
        var provisional: List<ResolvedDownloadCandidate> = emptyList()

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
                        val resolved = deviceCandidates.map { ResolvedDownloadCandidate(book, it) }
                        if (isWeakPlaylistResult(book, deviceCandidates)) {
                            if (provisional.isEmpty()) provisional = resolved
                        } else {
                            return resolved
                        }
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
                    val resolved = selected.map { ResolvedDownloadCandidate(book, it) }
                    if (isWeakPlaylistResult(book, selected)) {
                        if (provisional.isEmpty()) provisional = resolved
                    } else {
                        return resolved
                    }
                }
            }
        return provisional
    }

    private fun isWeakPlaylistResult(book: SourceBook, candidates: List<DownloadCandidate>): Boolean =
        book.sourceId in PLAYLIST_PROVIDERS &&
            candidates.size == 1 &&
            candidates.single().type == DownloadType.DIRECT_FILE

    /** Backwards-compatible single-payload API for callers that genuinely need one candidate. */
    suspend fun resolve(sources: List<SourceBook>): ResolvedDownloadCandidate? =
        resolveAll(sources).firstOrNull()
}
