package org.audoiboo.tracker

internal object PlayerLogic {
    data class Progress(
        val fraction: Float,
        val started: Boolean,
        val finished: Boolean,
        /** 1-based original track index for UI; 0 means no track has started. */
        val currentTrack: Int,
        val playableCount: Int
    )

    data class AutoTransition(
        val targetIndex: Int?,
        val shouldPlay: Boolean,
        val consumeSleepAtEnd: Boolean
    )

    fun automaticTransition(
        trackCount: Int,
        currentIndex: Int,
        brokenIndices: Set<Int>,
        sleepAtEndOfTrack: Boolean
    ): AutoTransition {
        if (sleepAtEndOfTrack) return AutoTransition(null, false, true)
        if (currentIndex !in brokenIndices) return AutoTransition(currentIndex.takeIf { it in 0 until trackCount }, true, false)
        val next = nextPlayableIndex(trackCount, currentIndex, brokenIndices)
        return AutoTransition(next, next != null, false)
    }

    fun smartRewindForGapMs(gapMs: Long): Long = when {
        gapMs.coerceAtLeast(0L) >= 24 * 60 * 60_000L -> 60_000L
        gapMs >= 60 * 60_000L -> 30_000L
        gapMs >= 5 * 60_000L -> 10_000L
        else -> 0L
    }

    fun statusForProgress(fraction: Float, hasStarted: Boolean): String = when {
        fraction >= .95f -> "READ"
        hasStarted || fraction > 0f -> "READING"
        else -> "NEW"
    }

    fun aggregateProgress(trackCount: Int, lastStartedIndex: Int, positionMs: Long, durationMs: Long): Float {
        if (trackCount <= 0 || lastStartedIndex !in 0 until trackCount) return 0f
        val within = if (durationMs > 0) (positionMs.coerceAtLeast(0L).toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        return ((lastStartedIndex + within) / trackCount.toFloat()).coerceIn(0f, 1f)
    }

    fun brokenIndices(trackUris: List<String>, brokenUris: Set<String>): Set<Int> {
        if (trackUris.isEmpty() || brokenUris.isEmpty()) return emptySet()
        return trackUris.indices.filterTo(linkedSetOf()) { trackUris[it] in brokenUris }
    }

    fun playableCount(trackCount: Int, brokenIndices: Set<Int>): Int {
        if (trackCount <= 0) return 0
        return (0 until trackCount).count { it !in brokenIndices }
    }

    fun bookProgress(
        positionsMs: List<Long>,
        durationsMs: List<Long>,
        brokenIndices: Set<Int> = emptySet(),
        trackerRead: Boolean = false
    ): Progress {
        val count = maxOf(positionsMs.size, durationsMs.size)
        if (count == 0) return Progress(if (trackerRead) 1f else 0f, false, trackerRead, 0, 0)
        val playable = (0 until count).filterNot { it in brokenIndices }
        if (playable.isEmpty()) {
            val started = positionsMs.any { it > 0L }
            return Progress(if (trackerRead) 1f else 0f, started, trackerRead, 0, 0)
        }
        val lastStartedOriginal = playable.lastOrNull { index -> (positionsMs.getOrNull(index) ?: 0L) > 0L }
        if (lastStartedOriginal == null) return Progress(if (trackerRead) 1f else 0f, false, trackerRead, 0, playable.size)
        val playableOrdinal = playable.indexOf(lastStartedOriginal)
        val rawPosition = (positionsMs.getOrNull(lastStartedOriginal) ?: 0L).coerceAtLeast(0L)
        val duration = (durationsMs.getOrNull(lastStartedOriginal) ?: 0L).coerceAtLeast(0L)
        val boundedPosition = if (duration > 0L) rawPosition.coerceAtMost(duration) else rawPosition
        val within = if (duration > 0L) (boundedPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val fraction = ((playableOrdinal + within) / playable.size.toFloat()).coerceIn(0f, 1f)
        val lastPlayable = playable.last()
        val finishedByPosition = lastStartedOriginal == lastPlayable && duration > 0L && within >= .95f
        val finished = trackerRead || finishedByPosition
        return Progress(if (finished) 1f else fraction, true, finished, lastStartedOriginal + 1, playable.size)
    }

    fun currentBookProgress(trackCount: Int, currentIndex: Int, positionMs: Long, durationMs: Long, brokenIndices: Set<Int> = emptySet()): Float {
        if (trackCount <= 0 || currentIndex !in 0 until trackCount) return 0f
        val playable = (0 until trackCount).filterNot { it in brokenIndices }
        val ordinal = playable.indexOf(currentIndex)
        if (ordinal < 0 || playable.isEmpty()) return 0f
        val within = if (durationMs > 0L) positionMs.coerceIn(0L, durationMs).toFloat() / durationMs else 0f
        return ((ordinal + within) / playable.size.toFloat()).coerceIn(0f, 1f)
    }

    fun safeResumePosition(positionMs: Long, durationMs: Long, rewindMs: Long): Long {
        val upper = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return (positionMs.coerceAtLeast(0L).coerceAtMost(upper) - rewindMs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    fun nextPlayableIndex(trackCount: Int, currentIndex: Int, brokenIndices: Set<Int>): Int? {
        if (trackCount <= 0) return null
        val start = (currentIndex + 1).coerceAtLeast(0)
        return (start until trackCount).firstOrNull { it !in brokenIndices }
    }

    fun previousPlayableIndex(trackCount: Int, currentIndex: Int, brokenIndices: Set<Int>): Int? {
        if (trackCount <= 0) return null
        val start = (currentIndex - 1).coerceAtMost(trackCount - 1)
        if (start < 0) return null
        return (start downTo 0).firstOrNull { it !in brokenIndices }
    }

    fun resumePlayableIndex(trackCount: Int, requestedIndex: Int, brokenIndices: Set<Int>): Int? {
        if (trackCount <= 0) return null
        val requested = requestedIndex.coerceIn(0, trackCount - 1)
        if (requested !in brokenIndices) return requested
        return (requested + 1 until trackCount).firstOrNull { it !in brokenIndices }
            ?: (requested - 1 downTo 0).firstOrNull { it !in brokenIndices }
    }

    fun parseBookNumber(value: String): Int? {
        val patterns = listOf(
            Regex("(?i)(?:книга|частина|часть|том|book)\\s*[№#:-]?\\s*(\\d{1,3})"),
            Regex("^\\s*(\\d{1,3})\\s*[-.:]"),
            Regex("(?:^|\\s)(\\d{1,3})(?:\\s*\\(\\d+\\))?(?:\\.|\\s|$)")
        )
        return patterns.firstNotNullOfOrNull { it.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() }
    }

    fun moveQueue(current: List<String>, from: Int, to: Int): List<String> {
        if (from !in current.indices || to !in current.indices || from == to) return current
        return current.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun playNext(current: List<String>, activeDir: String?, dir: String): List<String> {
        val base = current.filterNot { it == dir }.toMutableList()
        val active = activeDir?.let(base::indexOf) ?: -1
        base.add(if (active >= 0) active + 1 else 0, dir)
        return base
    }
}
