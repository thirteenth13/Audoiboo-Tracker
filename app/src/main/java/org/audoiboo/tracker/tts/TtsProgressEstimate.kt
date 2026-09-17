package org.audoiboo.tracker.tts

import kotlin.math.roundToLong

data class TtsProgressEstimate(
    val completedChunks: Int,
    val totalChunks: Int,
    val elapsedMs: Long,
    val chunksPerMinute: Double?,
    val remainingMs: Long?,
) {
    val percent: Int
        get() = if (totalChunks <= 0) 0 else ((completedChunks.coerceIn(0, totalChunks) * 100.0) / totalChunks).toInt()
}

/** Estimates remaining generation time from measured progress on the current device. */
internal object TtsProgressEstimator {
    private const val MIN_COMPLETED_FOR_ETA = 2

    fun estimate(startChunk: Int, currentChunk: Int, totalChunks: Int, elapsedMs: Long): TtsProgressEstimate {
        val total = totalChunks.coerceAtLeast(0)
        val current = currentChunk.coerceAtLeast(0).coerceAtMost(total)
        val generated = (current - startChunk.coerceAtLeast(0)).coerceAtLeast(0)
        if (generated < MIN_COMPLETED_FOR_ETA || elapsedMs <= 0L) {
            return TtsProgressEstimate(current, total, elapsedMs.coerceAtLeast(0L), null, null)
        }
        val chunksPerMs = generated.toDouble() / elapsedMs.toDouble()
        val remaining = (total - current).coerceAtLeast(0)
        return TtsProgressEstimate(
            completedChunks = current,
            totalChunks = total,
            elapsedMs = elapsedMs,
            chunksPerMinute = chunksPerMs * 60_000.0,
            remainingMs = (remaining / chunksPerMs).roundToLong(),
        )
    }

    fun formatRemaining(remainingMs: Long): String {
        val totalMinutes = ((remainingMs.coerceAtLeast(0L) + 59_999L) / 60_000L)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 && minutes > 0 -> "${hours} год ${minutes} хв"
            hours > 0 -> "${hours} год"
            else -> "${minutes.coerceAtLeast(1)} хв"
        }
    }
}
