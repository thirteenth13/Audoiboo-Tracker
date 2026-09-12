package org.audoiboo.tracker.tts

import java.io.File

fun interface SherpaAdapterFactory {
    fun create(modelFile: File): SherpaOnnxAdapter
}

class SherpaOnnxTtsProvider(
    private val modelManager: VoiceModelManager,
    private val models: Map<String, VoiceModelSpec>,
    private val voices: List<TtsVoice>,
    private val adapterFactory: SherpaAdapterFactory,
    private val audioWriter: (SherpaAudio, File) -> Unit,
) : TtsProvider {
    override val id: String = "sherpa-onnx"
    override val supportedLanguages: Set<String> = voices.map { it.language }.toSet()

    private var activeKey: String? = null
    private var activeAdapter: SherpaOnnxAdapter? = null

    override suspend fun getVoices(language: String): List<TtsVoice> =
        voices.filter { voice -> languageMatches(voice.language, language) }

    override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
        require(supportsLanguage(request.language)) { "Unsupported TTS language: ${request.language}" }
        require(languageMatches(request.voice.language, request.language)) { "Voice language does not match request" }
        val spec = models[request.voice.modelId] ?: error("Unknown voice model: ${request.voice.modelId}")
        require(spec.version == request.voice.modelVersion) { "Voice model version mismatch" }
        require(languageMatches(spec.language, request.language)) { "Model language does not match request" }

        val modelFile = modelManager.verify(spec).getOrThrow()
        val adapter = adapterFor(request.voice.stableKey, modelFile)
        val audio = adapter.synthesize(request.text, request.voice.speakerId ?: 0, request.speed)
        val output = File(request.outputPath)
        output.parentFile?.mkdirs()
        audioWriter(audio, output)
        return TtsSynthesisResult(output.absolutePath, audio.sampleRateHz, audio.durationMs())
    }

    @Synchronized
    private fun adapterFor(key: String, modelFile: File): SherpaOnnxAdapter {
        if (activeKey != key || activeAdapter == null) {
            activeAdapter?.close()
            activeAdapter = adapterFactory.create(modelFile)
            activeKey = key
        }
        return checkNotNull(activeAdapter)
    }

    @Synchronized
    override fun close() {
        activeAdapter?.close()
        activeAdapter = null
        activeKey = null
    }

    private fun languageMatches(a: String, b: String): Boolean {
        val left = a.lowercase()
        val right = b.lowercase()
        return left == right || left.startsWith("$right-") || right.startsWith("$left-")
    }
}
