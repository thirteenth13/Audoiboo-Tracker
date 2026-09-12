package org.audoiboo.tracker.tts

data class TtsVoice(
    val id: String,
    val displayName: String,
    val language: String,
    val modelId: String,
    val modelVersion: String,
    val speakerId: Int? = null,
) {
    init {
        require(id.isNotBlank())
        require(displayName.isNotBlank())
        require(language.isNotBlank())
        require(modelId.isNotBlank())
        require(modelVersion.isNotBlank())
        require(speakerId == null || speakerId >= 0)
    }

    val stableKey: String
        get() = listOf(id, language.lowercase(), modelId, modelVersion, speakerId ?: -1).joinToString(":")
}

data class TtsSynthesisRequest(
    val text: String,
    val language: String,
    val voice: TtsVoice,
    val speed: Float = 1.0f,
    val outputPath: String,
) {
    init {
        require(text.isNotBlank())
        require(language.isNotBlank())
        require(speed in 0.5f..2.0f)
        require(outputPath.isNotBlank())
    }
}

data class TtsSynthesisResult(
    val outputPath: String,
    val sampleRateHz: Int,
    val durationMs: Long,
) {
    init {
        require(outputPath.isNotBlank())
        require(sampleRateHz > 0)
        require(durationMs >= 0L)
    }
}

interface TtsProvider : AutoCloseable {
    val id: String
    val supportedLanguages: Set<String>

    suspend fun getVoices(language: String): List<TtsVoice>
    suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult

    fun supportsLanguage(language: String): Boolean {
        val normalized = language.lowercase()
        return supportedLanguages.any { supported ->
            val s = supported.lowercase()
            normalized == s || normalized.startsWith("$s-") || s.startsWith("$normalized-")
        }
    }

    override fun close() = Unit
}
