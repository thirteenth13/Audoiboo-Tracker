package org.audoiboo.tracker.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File

internal class SupertonicAndroidAdapter private constructor(
    private val tts: OfflineTts,
) : SherpaOnnxAdapter {
    override fun synthesize(text: String, speakerId: Int, speed: Float): SherpaAudio =
        error("Supertonic synthesis requires an explicit language")

    override fun synthesize(text: String, speakerId: Int, speed: Float, language: String): SherpaAudio {
        require(text.isNotBlank()) { "TTS text must not be blank" }
        require(speakerId >= 0) { "Speaker id must be non-negative" }
        require(speed > 0f) { "TTS speed must be positive" }
        val lang = language.trim().lowercase().substringBefore('-').substringBefore('_')
        require(lang == "uk" || lang == "ru") { "Unsupported Supertonic language for Audoiboo: $language" }

        val audio = tts.generateWithConfig(
            text = text,
            config = GenerationConfig(
                sid = speakerId,
                numSteps = NUM_STEPS,
                speed = speed,
                extra = mapOf("lang" to lang),
            ),
        )
        return SherpaAudio(audio.samples, audio.sampleRate)
    }

    override fun close() = tts.release()

    companion object {
        const val DURATION_PREDICTOR = "duration_predictor.int8.onnx"
        const val TEXT_ENCODER = "text_encoder.int8.onnx"
        const val VECTOR_ESTIMATOR = "vector_estimator.int8.onnx"
        const val VOCODER = "vocoder.int8.onnx"
        const val TTS_JSON = "tts.json"
        const val UNICODE_INDEXER = "unicode_indexer.bin"
        const val VOICE_STYLE = "voice.bin"
        const val NUM_STEPS = 8

        val REQUIRED_FILES = listOf(
            DURATION_PREDICTOR,
            TEXT_ENCODER,
            VECTOR_ESTIMATOR,
            VOCODER,
            TTS_JSON,
            UNICODE_INDEXER,
            VOICE_STYLE,
        )

        private fun isRuntimeFile(file: File): Boolean = file.isFile && file.length() > 0L

        fun isModel(modelFile: File): Boolean {
            val dir = modelFile.parentFile ?: return false
            return modelFile.name == DURATION_PREDICTOR && REQUIRED_FILES.all { isRuntimeFile(File(dir, it)) }
        }

        fun create(modelFile: File, numThreads: Int): SupertonicAndroidAdapter {
            require(isRuntimeFile(modelFile)) { "Sherpa model file is missing or empty: ${modelFile.absolutePath}" }
            require(numThreads > 0)
            val dir = requireNotNull(modelFile.parentFile)
            fun path(name: String): String {
                val file = File(dir, name)
                require(isRuntimeFile(file)) { "Supertonic runtime file is missing or empty: ${file.absolutePath}" }
                return file.absolutePath
            }

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = path(DURATION_PREDICTOR),
                        textEncoder = path(TEXT_ENCODER),
                        vectorEstimator = path(VECTOR_ESTIMATOR),
                        vocoder = path(VOCODER),
                        ttsJson = path(TTS_JSON),
                        unicodeIndexer = path(UNICODE_INDEXER),
                        voiceStyle = path(VOICE_STYLE),
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
            )
            return SupertonicAndroidAdapter(OfflineTts(assetManager = null, config = config))
        }
    }
}
