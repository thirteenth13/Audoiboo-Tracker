package org.audoiboo.tracker.tts

data class SherpaAudio(
    val samples: FloatArray,
    val sampleRateHz: Int,
) {
    init {
        require(sampleRateHz > 0)
    }
}

interface SherpaOnnxAdapter : AutoCloseable {
    fun synthesize(text: String, speakerId: Int, speed: Float): SherpaAudio
    override fun close() = Unit
}

fun SherpaAudio.durationMs(): Long =
    if (samples.isEmpty()) 0L else samples.size * 1000L / sampleRateHz
