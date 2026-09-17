package org.audoiboo.tracker.tts

import android.content.Context
import java.io.File
import java.util.UUID
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner

/** Prepared, fully verified inputs that can be persisted and handed to the background generator. */
data class PreparedSherpaBookTts(
    val model: VoiceModelSpec,
    val voice: TtsVoice,
    val session: TtsSession,
    val chunkCount: Int,
    val quality: TtsQuality = TtsQuality.FAST,
    val engineFamily: TtsEngineFamily = TtsEngineFamily.PIPER_VITS,
)

/**
 * Application-level entry point from an imported ebook to the existing resumable Sherpa pipeline.
 * Model download/verification is intentionally synchronous; callers should invoke prepare/enqueue
 * from an IO coroutine or another background thread.
 */
class SherpaBookTtsCoordinator(
    private val installer: SherpaVoiceInstaller,
    private val packageResolver: (String, TtsQuality) -> SherpaVoicePackage? = SherpaVoiceCatalog::forLanguage,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun prepare(
        document: BookDocument,
        speed: Float = 1.0f,
        quality: TtsQuality = TtsQuality.FAST,
    ): Result<PreparedSherpaBookTts> = runCatching {
        require(speed in 0.5f..2.0f) { "TTS speed must be between 0.5 and 2.0" }
        require(document.chapters.isNotEmpty()) { "Book has no chapters to synthesize" }

        val language = document.language?.trim()?.takeIf(String::isNotBlank)
            ?: error("Book language is required for local TTS")
        val pkg = packageResolver(language, quality)
            ?: error("No Sherpa ${quality.name.lowercase()} model for language: $language")
        val model = installer.ensureInstalled(pkg).getOrThrow()
        val voice = TtsVoice(
            id = pkg.modelId,
            displayName = pkg.displayName,
            language = pkg.language,
            modelId = model.modelId,
            modelVersion = model.version,
            speakerId = 0,
        )
        val plan = TtsSynthesisPlanner.build(document)
        require(plan.chunkCount > 0) { "Book has no speakable text" }
        val sessionId = sessionIdFactory().trim()
        require(sessionId.isNotBlank()) { "TTS session id must not be blank" }
        val session = TtsSession(
            sessionId = sessionId,
            providerId = PROVIDER_ID,
            voice = voice,
            documentFingerprint = plan.documentFingerprint,
            speed = speed,
            quality = quality,
            engineFamily = pkg.engineFamily,
        )
        PreparedSherpaBookTts(
            model = model,
            voice = voice,
            session = session,
            chunkCount = plan.chunkCount,
            quality = quality,
            engineFamily = pkg.engineFamily,
        )
    }

    fun enqueue(
        context: Context,
        document: BookDocument,
        outputDir: File,
        speed: Float = 1.0f,
        quality: TtsQuality = TtsQuality.FAST,
    ): Result<TtsSession> = runCatching {
        val prepared = prepare(document, speed, quality).getOrThrow()
        TtsGenerationScheduler.enqueue(
            context = context.applicationContext,
            session = prepared.session,
            document = document,
            model = prepared.model,
            outputDir = outputDir,
        )
        prepared.session
    }

    companion object {
        private const val PROVIDER_ID = "sherpa-onnx"

        fun create(context: Context): SherpaBookTtsCoordinator {
            val manager = VoiceModelManager(TtsModelStorage.root(context.applicationContext))
            return SherpaBookTtsCoordinator(SherpaVoiceInstaller(manager))
        }
    }
}
