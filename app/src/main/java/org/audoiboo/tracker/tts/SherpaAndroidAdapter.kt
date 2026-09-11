package org.audoiboo.tracker.tts

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/** Concrete Sherpa-ONNX Android adapter for file-backed VITS voice models. */
internal class SherpaAndroidAdapter private constructor(
    private val tts: OfflineTts,
) : SherpaOnnxAdapter {
    override fun synthesize(text: String, speakerId: Int, speed: Float): SherpaAudio {
        require(text.isNotBlank()) { "TTS text must not be blank" }
        require(speakerId >= 0) { "Speaker id must be non-negative" }
        require(speed > 0f) { "TTS speed must be positive" }

        val audio = tts.generate(text = text, sid = speakerId, speed = speed)
        return SherpaAudio(audio.samples, audio.sampleRate)
    }

    override fun close() {
        tts.release()
    }

    companion object {
        /**
         * Builds a process-durable factory. The native engine is created lazily only when a worker
         * actually starts synthesis, so installing the factory during Application.onCreate() does
         * not load a model or allocate inference memory.
         */
        fun factory(numThreads: Int = 4): SherpaAdapterFactory {
            require(numThreads > 0)
            return SherpaAdapterFactory { modelFile -> create(modelFile, numThreads) }
        }

        private fun create(modelFile: File, numThreads: Int): SherpaAndroidAdapter {
            require(modelFile.isFile) { "Sherpa model file is missing: ${modelFile.absolutePath}" }
            val modelDir = requireNotNull(modelFile.parentFile) { "Sherpa model has no parent directory" }
            val tokens = File(modelDir, "tokens.txt")
            require(tokens.isFile) { "Sherpa tokens.txt is missing in ${modelDir.absolutePath}" }

            val lexicon = File(modelDir, "lexicon.txt").takeIf(File::isFile)
            val dataDir = File(modelDir, "espeak-ng-data").takeIf(File::isDirectory)

            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        lexicon = lexicon?.absolutePath.orEmpty(),
                        tokens = tokens.absolutePath,
                        dataDir = dataDir?.absolutePath.orEmpty(),
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
            )
            return SherpaAndroidAdapter(OfflineTts(assetManager = null, config = config))
        }
    }
}
