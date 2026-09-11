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
    val modelFileName: String,
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
 * Small first-party catalog for the initial RU/UA experiment.
 *
 * We deliberately pin upstream release assets and their GitHub-provided SHA-256 digests instead of
 * following a mutable "latest" URL. The int8 variants keep the first mobile download reasonably
 * small while preserving the exact Sherpa/Piper package layout (tokens.txt + espeak-ng-data).
 */
object SherpaVoiceCatalog {
    const val VERSION = "tts-models-2025-12-02"

    val russian = SherpaVoicePackage(
        modelId = "sherpa-vits-piper-ru-ruslan-medium-int8",
        version = VERSION,
        language = "ru",
        displayName = "Русский — Ruslan",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-ruslan-medium-int8.tar.bz2",
        archiveSha256 = "64fc4cfe9f4e9701a2a67d1ef17f2c0b8353cf4b36dd1eb0207ccafad9a2d8c7",
        archiveSizeBytes = 22_031_578L,
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

    val all: List<SherpaVoicePackage> = listOf(ukrainian, russian)

    fun forLanguage(language: String): SherpaVoicePackage? {
        val normalized = language.trim().lowercase().substringBefore('-').substringBefore('_')
        return all.firstOrNull { it.language == normalized }
    }
}
