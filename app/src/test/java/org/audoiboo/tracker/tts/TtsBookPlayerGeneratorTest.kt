package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsBookPlayerGeneratorTest {
    private fun document() = BookDocument(
        title = "Книга",
        authors = listOf("Автор"),
        language = "uk",
        series = "Серія",
        seriesNumber = 1,
        chapters = listOf(
            BookChapter(0, "Перша", listOf("Перше речення. ".repeat(80))),
            BookChapter(1, "Друга", listOf("Друге речення. ".repeat(80))),
        ),
    )

    private fun session(document: BookDocument, providerId: String = "fake") = TtsSession(
        sessionId = "player-book",
        providerId = providerId,
        voice = TtsVoice("uk-test", "UK Test", "uk", "model", "1"),
        documentFingerprint = TtsSynthesisPlanner.fingerprint(document),
        speed = 1f,
    )

    @Test
    fun publishesOnlyAfterBookCompletes() = runBlocking {
        val root = Files.createTempDirectory("tts-player-complete").toFile()
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
        val document = document()
        val published = mutableListOf<TtsBookGenerationResult>()
        val core = TtsBookGenerator(TtsChapterGenerator(provider, File(root, "work")))
        val generator = TtsBookPlayerGenerator(core, { _, result -> published += result })

        val result = generator.generate(document, session(document), File(root, "out"))

        assertEquals(TtsSessionState.COMPLETED, result.session.state)
        assertEquals(1, published.size)
        assertEquals(result, published.single())
        assertEquals(2, result.chapters.size)
        assertTrue(result.chapters.all { it.audioFile.isFile })
    }

    @Test
    fun doesNotPublishFailedPartialBook() = runBlocking {
        val root = Files.createTempDirectory("tts-player-failed").toFile()
        val provider = object : TtsProvider {
            override val id = "fake"
            override val supportedLanguages = setOf("uk")
            override suspend fun getVoices(language: String) = emptyList<TtsVoice>()
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
                if (request.text.contains("Друге речення")) error("synthetic failure")
                val file = File(request.outputPath)
                Pcm16Wav.write(SherpaAudio(FloatArray(2400) { 0.1f }, 24000), file)
                return TtsSynthesisResult(file.absolutePath, 24000, 100)
            }
        }
        val document = document()
        var publishCount = 0
        val core = TtsBookGenerator(TtsChapterGenerator(provider, File(root, "work")))
        val generator = TtsBookPlayerGenerator(core, { _, _ -> publishCount++ })

        val result = generator.generate(document, session(document), File(root, "out"))

        assertEquals(TtsSessionState.FAILED, result.session.state)
        assertEquals(0, publishCount)
        assertEquals(listOf(0), result.chapters.map { it.chapterIndex })
    }

    @Test
    fun resumesPersistedCheckpointAndDeletesItAfterPublish() = runBlocking {
        val root = Files.createTempDirectory("tts-player-resume").toFile()
        val document = document()
        val initial = session(document)
        val plan = TtsSynthesisPlanner.build(document)
        val firstChapterChunkCount = plan.chapters.first().chunks.size
        val output = File(root, "out").apply { mkdirs() }
        Pcm16Wav.write(SherpaAudio(FloatArray(2400) { 0.1f }, 24000), File(output, "chapter-0000.wav"))

        val store = TtsSessionStore(File(root, "sessions"))
        store.save(
            initial.copy(
                state = TtsSessionState.FAILED,
                nextGlobalChunkIndex = firstChapterChunkCount,
                completedChapterIndexes = setOf(0),
                lastError = "stopped",
            )
        )

        val synthesizedTexts = mutableListOf<String>()
        val provider = object : TtsProvider {
            override val id = "fake"
            override val supportedLanguages = setOf("uk")
            override suspend fun getVoices(language: String) = emptyList<TtsVoice>()
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
                synthesizedTexts += request.text
                val file = File(request.outputPath)
                Pcm16Wav.write(SherpaAudio(FloatArray(2400) { 0.1f }, 24000), file)
                return TtsSynthesisResult(file.absolutePath, 24000, 100)
            }
        }
        var publishCount = 0
        val core = TtsBookGenerator(TtsChapterGenerator(provider, File(root, "work")))
        val generator = TtsBookPlayerGenerator(
            core,
            { _, _ -> publishCount++ },
            sessionStoreForTest = store,
        )

        val result = generator.generate(document, initial, output)

        assertEquals(TtsSessionState.COMPLETED, result.session.state)
        assertEquals(setOf(0, 1), result.session.completedChapterIndexes)
        assertEquals(1, publishCount)
        assertTrue(synthesizedTexts.isNotEmpty())
        assertTrue(synthesizedTexts.all { it.contains("Друге речення") })
        assertNull(store.load(initial.sessionId))
    }
}
