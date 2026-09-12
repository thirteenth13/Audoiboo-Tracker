package org.audoiboo.tracker.tts

import java.io.File
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsGenerationSchedulerPolicyTest {
    private val document = BookDocument(
        title = "Книга",
        authors = listOf("Автор"),
        language = "uk",
        series = null,
        seriesNumber = null,
        chapters = listOf(BookChapter(0, "Розділ", listOf("Текст для озвучення"))),
    )

    private val model = VoiceModelSpec(
        modelId = "model-uk",
        version = "v1",
        language = "uk",
        sha256 = "1".repeat(64),
        fileName = "model.int8.onnx",
    )

    private val voice = TtsVoice(
        id = "voice-uk",
        displayName = "Український голос",
        language = "uk",
        modelId = model.modelId,
        modelVersion = model.version,
        speakerId = 0,
    )

    private val session = TtsSession(
        sessionId = "session-1",
        providerId = "sherpa-onnx",
        voice = voice,
        documentFingerprint = TtsSynthesisPlanner.fingerprint(document),
        speed = 1.0f,
        state = TtsSessionState.PAUSED,
        nextGlobalChunkIndex = 2,
    )

    @Test
    fun acceptsMatchingPersistedJob() {
        TtsGenerationScheduler.validateResumeJob(session, job(document = document, model = model))
    }

    @Test
    fun rejectsChangedDocumentBeforeResume() {
        val changed = document.copy(
            chapters = listOf(BookChapter(0, "Розділ", listOf("Інший текст"))),
        )
        val failure = runCatching {
            TtsGenerationScheduler.validateResumeJob(session, job(document = changed, model = model))
        }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("document mismatch"))
    }

    @Test
    fun rejectsDifferentModelBeforeResume() {
        val different = model.copy(modelId = "other-model")
        val failure = runCatching {
            TtsGenerationScheduler.validateResumeJob(session, job(document = document, model = different))
        }

        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("model mismatch"))
    }

    private fun job(document: BookDocument, model: VoiceModelSpec) = TtsBackgroundBookJob(
        sessionId = session.sessionId,
        document = document,
        model = model,
        outputDir = File(System.getProperty("java.io.tmpdir"), "tts-output").absolutePath,
    )
}
