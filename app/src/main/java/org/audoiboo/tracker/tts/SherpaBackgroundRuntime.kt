package org.audoiboo.tracker.tts

import android.content.Context
import java.io.File

/** Reconstructs a persisted Sherpa TTS job and runs it through the resumable book pipeline. */
internal class SherpaBackgroundRuntime(
    private val adapterFactory: SherpaAdapterFactory,
) : TtsBackgroundRuntime {
    override suspend fun run(context: Context, sessionId: String): TtsSessionState {
        val filesRoot = File(context.filesDir, "tts")
        val sessionStore = TtsSessionStore(File(filesRoot, "sessions"))
        val jobStore = TtsBackgroundJobStore(File(filesRoot, "jobs"))
        val job = jobStore.load(sessionId) ?: return TtsSessionState.FAILED
        val session = sessionStore.load(sessionId) ?: return TtsSessionState.FAILED

        require(session.providerId == PROVIDER_ID) { "Background TTS provider mismatch" }
        require(session.voice.modelId == job.model.modelId) { "Background TTS model mismatch" }
        require(session.voice.modelVersion == job.model.version) { "Background TTS model version mismatch" }

        val provider = SherpaOnnxTtsProvider(
            modelManager = VoiceModelManager(File(filesRoot, "models")),
            models = mapOf(job.model.modelId to job.model),
            voices = listOf(session.voice),
            adapterFactory = adapterFactory,
            audioWriter = Pcm16Wav::write,
        )
        return try {
            val chapterGenerator = TtsChapterGenerator(provider, File(filesRoot, "work"))
            val result = TtsBookPlayerGenerator(context, TtsBookGenerator(chapterGenerator)).generate(
                document = job.document,
                initialSession = session,
                outputDir = File(job.outputDir),
            )
            if (result.session.state == TtsSessionState.COMPLETED) {
                jobStore.delete(sessionId)
            }
            result.session.state
        } finally {
            provider.close()
        }
    }

    companion object {
        private const val PROVIDER_ID = "sherpa-onnx"
    }
}

/** Installs the concrete background runtime once a native Sherpa adapter factory is available. */
internal object SherpaBackgroundRuntimeInstaller {
    fun install(adapterFactory: SherpaAdapterFactory) {
        TtsBackgroundRuntimeRegistry.install(SherpaBackgroundRuntime(adapterFactory))
    }
}
