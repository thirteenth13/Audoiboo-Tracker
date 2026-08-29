package org.audoiboo.tracker

/** Pure merge policy for playback positions that may still be waiting on an async Room write. */
internal object TrackPositionSnapshotPolicy {
    fun merge(
        room: Map<String, Long>,
        cached: Map<String, Long>,
        pending: Map<String, Long>
    ): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        fun add(source: Map<String, Long>) {
            source.forEach { (uri, value) ->
                if (uri.isNotBlank()) out[uri] = value.coerceAtLeast(0L)
            }
        }
        add(room)
        add(cached)
        add(pending)
        return out
    }
}
