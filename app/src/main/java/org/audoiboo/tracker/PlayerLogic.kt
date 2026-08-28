package org.audoiboo.tracker

internal object PlayerLogic {
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
        val within = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        return ((lastStartedIndex + within) / trackCount.toFloat()).coerceIn(0f, 1f)
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
