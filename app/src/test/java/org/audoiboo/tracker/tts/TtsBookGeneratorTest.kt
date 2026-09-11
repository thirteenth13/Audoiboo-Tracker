package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsBookGeneratorTest {
    private fun document() = BookDocument(
        title = "Книга",
        authors = listOf("Автор"),
        language = "uk",
        series = null,
        seriesNumber = null,
        chapters = listOf(
            BookChapter(0, "Перший", listOf("Перше речення. ".repeat(80))),
            BookChapter(1, "Другий", listOf("Друге речення. ".repeat(80))),
        ),
    )

    @Test fun generatesChapterFilesAndCompletesSession() = runBlocking {
        val root = Files.createTempDirectory("tts-book").toFile()
        val provider = object : TtsProvider {
            override val id = "fake"
            override val supportedLanguages = setOf("uk")
            override suspend fun getVoices(language: String) = emptyList<TtsVoice>()
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
                val file = File(request.outputPath)
                Pcm16Wav.write(SherpaAudio(FloatArray(2400) { 0.1f }, 24000), file)
                return TtsSynthesisResult(file.absolutePath, 24000, 100)
            }
        }
        val doc = document()
        val voice = TtsVoice("uk-test", "UK Test", "uk", "model", "1")
        val session = TtsSession(
            sessionId = "book-1",
            providerId = provider.id,
            voice = voice,
            documentFingerprint = TtsSynthesisPlanner.fingerprint(doc),
            speed = 1f,
        )
        val generator = TtsBookGenerator(TtsChapterGenerator(provider, File(root, "work")))
        val result = generator.generate(doc, session, File(root, "out"))

        assertEquals(TtsSessionState.COMPLETED, result.session.state)
        assertEquals(setOf(0, 1), result.session.completedChapterIndexes)
        assertEquals(listOf(0, 1), result.chapters.map { it.chapterIndex })
        assertTrue(result.chapters.all { it.audioFile.isFile && it.audioFile.length() > 44L })
    }

    @Test fun returnsOnlyCommittedChaptersWhenGenerationFails() = runBlocking {
        val root = Files.createTempDirectory("tts-book-fail").toFile()
        var calls = 0
        val provider = object : TtsProvider {
            override val id = "fake"
            override val supportedLanguages = setOf("uk")
            override suspend fun getVoices(language: String) = emptyList<TtsVoice>()
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
                calls++
                if (calls > 2) error("synthetic failure")
                val file = File(request.outputPath)
                Pcm16Wav.write(SherpaAudio(FloatArray(2400) { 0.1f }, 24000), file)
                return TtsSynthesisResult(file.absolutePath, 24000, 100)
            }
        }
        val doc = document()
        val voice = TtsVoice("uk-test", "UK Test", "uk", "model", "1")
        val session = TtsSession(
            sessionId = "book-2",
            providerId = provider.id,
            voice = voice,
            documentFingerprint = TtsSynthesisPlanner.fingerprint(doc),
            speed = 1f,
        )
        val generator = TtsBookGenerator(TtsChapterGenerator(provider, File(root, "work")))
        val result = generator.generate(doc, session, File(root, "out"))

        assertEquals(TtsSessionState.FAILED, result.session.state)
        assertTrue(result.chapters.size <= 1)
        assertTrue(result.chapters.all { it.chapterIndex in result.session.completedChapterIndexes })
    }
}
