package org.audoiboo.tracker.plugin

internal object DownloadCandidateSelectionPolicy {
    fun selectable(candidates: List<DownloadCandidate>): List<DownloadCandidate> = candidates
        .filter { it.type == DownloadType.ARCHIVE || it.type == DownloadType.DIRECT_FILE }
        .distinctBy { SourceKeys.normalizeUrl(it.url) }

    fun preferredWithinSource(candidates: List<DownloadCandidate>): List<DownloadCandidate> {
        val selectable = selectable(candidates)
        val bestArchive = selectable
            .filter { it.type == DownloadType.ARCHIVE }
            .maxByOrNull { it.priority }
        return bestArchive?.let(::listOf)
            ?: selectable
                .filter { it.type == DownloadType.DIRECT_FILE }
                .sortedByDescending { it.priority }
    }
}
