package org.audoiboo.tracker.tts

import java.io.File
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.junit.Assert.assertThrows
import org.junit.Test

class TtsGenerationSchedulerTest {
    @Test
    fun `matching high quality Supertonic job is accepted`() {
        val document = document()
        val session = session(document, TtsQuality.HIGH_QUALITY, TtsEngineFamily.SUPERTONIC)
        TtsGenerationScheduler.validateResumeJob(
            session,
            job(document, TtsQuality.HIGH_QUALITY, TtsEngineFamily.SUPERTONIC),
        )
    }

    @Test
    fun `quality mismatch is rejected`() {
        val document = document()
        val session = session(document, TtsQuality.HIGH_QUALITY, TtsEngineFamily.SUPERTONIC)
        val mismatched = job(document, TtsQuality.FAST, TtsEngineFamily.PIPER_VITS)
            .copy(engineFamily = TtsEngineFamily.SUPERTONIC)

        assertThrows(IllegalArgumentException::class.java) {
            TtsGenerationScheduler.validateResumeJob(session, mismatched)
        }
    }

    @Test
    fun `engine mismatch is rejected`() {
        val document = document()
        val session = session(document, TtsQuality.FAST, TtsEngineFamily.PIPER_VITS)
        val mismatched = job(document, TtsQuality.FAST, TtsEngineFamily.SUPERTONIC)

        assertThrows(IllegalArgumentException::class.java) {
            TtsGenerationScheduler.validateResumeJob(session, mismatched)
        }
    }

    private fun document() = BookDocument(
        title = "Test book",
        authors = listOf("Author"),
        language = "uk",
        series = null,
        seriesNumber = null,
        chapters = listOf(BookChapter(0, "Chapter", listOf("Text"))),
    )

    private fun session(document: BookDocument, quality: TtsQuality, engine: TtsEngineFamily): TtsSession =
        TtsSession(
            sessionId = "session-1",
            providerId = "sherpa-onnx",
            voice = TtsVoice("voice", "Voice", "uk", "model", "v1"),
            documentFingerprint = TtsSynthesisPlanner.fingerprint(document),
            speed = 1f,
            quality = quality,
            engineFamily = engine,
        )

    private fun job(document: BookDocument, quality: TtsQuality, engine: TtsEngineFamily) =
        TtsBackgroundBookJob(
            sessionId = "session-1",
            document = document,
            model = VoiceModelSpec("model", "v1", "uk", "a".repeat(64), "model.onnx"),
            outputDir = File(System.getProperty("java.io.tmpdir"), "tts-output").absolutePath,
            quality = quality,
            engineFamily = engine,
        )
}
