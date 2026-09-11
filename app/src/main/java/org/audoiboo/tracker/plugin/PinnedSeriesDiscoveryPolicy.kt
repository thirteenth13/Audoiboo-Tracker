package org.audoiboo.tracker.plugin

/**
 * Keeps a user-verified provider series authoritative during later automatic discovery.
 * If discovery finds another series for the same provider, the pinned finding wins.
 */
internal object PinnedSeriesDiscoveryPolicy {
    fun merge(
        pinned: List<SeriesDiscoveryFinding>,
        discovered: List<SeriesDiscoveryFinding>
    ): List<SeriesDiscoveryFinding> {
        if (pinned.isEmpty()) return discovered
        val pinnedSourceIds = pinned.mapTo(linkedSetOf()) { it.sourceId }
        return pinned + discovered.filterNot { it.sourceId in pinnedSourceIds }
    }
}
