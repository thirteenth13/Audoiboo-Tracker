package org.audoiboo.tracker.tts

/** A pinned downloadable Sherpa voice package published by the upstream tts-models release. */
data class SherpaVoicePackage(
    val modelId: String,
    val version: String,
    val language: String,
    val displayName: String,
    val archiveUrl: String,
    val archiveSha256: String,
    val archiveSizeBytes: Long,
    /** Primary runtime file used as the integrity anchor by [VoiceModelManager]. */
    val modelFileName: String,
    val engineFamily: TtsEngineFamily = TtsEngineFamily.PIPER_VITS,
) {
    init {
        require(modelId.isNotBlank())
        require(version.isNotBlank())
        require(language.isNotBlank())
        require(displayName.isNotBlank())
        require(archiveUrl.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"))
        require(archiveSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(archiveSizeBytes > 0)
        require(modelFileName.endsWith(".onnx"))
    }
}

/**
 * First-party local TTS catalog for RU/UA.
 *
 * All release assets are immutable/pinned by exact byte size and GitHub-provided SHA-256 digest.
 * FAST uses the small language-specific Piper/VITS packages. HIGH_QUALITY uses one shared
 * multilingual Supertonic 3 package; only the requested language tag differs between sessions.
 */
object SherpaVoiceCatalog {
    const val VERSION = "tts-models-2025-12-02"
    const val SUPERTONIC_VERSION = "supertonic-3-2026-05-11"

    val russian = SherpaVoicePackage(
        modelId = "sherpa-vits-piper-ru-ruslan-medium-int8",
        version = VERSION,
        language = "ru",
        displayName = "Русский — Ruslan",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium-int8.tar.bz2",
        archiveSha256 = "93b9c97e1a7c503b42d3d3983b9a8f76a1ff751d27575f79c9ad9a46a5ad73ac",
        archiveSizeBytes = 21_127_907L,
        modelFileName = "ru_RU-ruslan-medium.int8.onnx",
    )

    val ukrainian = SherpaVoicePackage(
        modelId = "sherpa-vits-piper-uk-ukrainian-tts-medium-int8",
        version = VERSION,
        language = "uk",
        displayName = "Українська — Ukrainian TTS",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-uk_UA-ukrainian_tts-medium-int8.tar.bz2",
        archiveSha256 = "9e3e20f311e3c486d989c3d481d9537741deb7beaeed1aa63c737574498b1e75",
        archiveSizeBytes = 22_767_075L,
        modelFileName = "uk_UA-ukrainian_tts-medium.int8.onnx",
    )

    /** One physical package serves both Ukrainian and Russian. */
    val supertonic3 = SherpaVoicePackage(
        modelId = "sherpa-onnx-supertonic-3-tts-int8",
        version = SUPERTONIC_VERSION,
        language = "multi",
        displayName = "Supertonic 3 — Висока якість",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
        archiveSha256 = "82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427",
        archiveSizeBytes = 128_774_318L,
        modelFileName = "duration_predictor.int8.onnx",
        engineFamily = TtsEngineFamily.SUPERTONIC,
    )

    val all: List<SherpaVoicePackage> = listOf(ukrainian, russian)

    fun forLanguage(language: String): SherpaVoicePackage? {
        val normalized = normalizeLanguage(language)
        return all.firstOrNull { it.language == normalized }
    }

    fun forLanguage(language: String, quality: TtsQuality): SherpaVoicePackage? {
        val normalized = normalizeLanguage(language)
        return when (quality) {
            TtsQuality.FAST -> all.firstOrNull { it.language == normalized }
            TtsQuality.HIGH_QUALITY -> if (normalized == "uk" || normalized == "ru") {
                // Keep the package/model identity shared so installing for the second language reuses
                // the same verified files. The per-session language drives GenerationConfig.extra.
                supertonic3.copy(language = normalized)
            } else null
        }
    }

    private fun normalizeLanguage(language: String): String =
        language.trim().lowercase().substringBefore('-').substringBefore('_')
}
