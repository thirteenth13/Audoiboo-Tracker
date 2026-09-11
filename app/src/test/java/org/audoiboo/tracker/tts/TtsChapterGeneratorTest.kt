package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.audoiboo.tracker.ebook.TtsChapterPlan
import org.audoiboo.tracker.ebook.TtsPlannedChunk
import org.audoiboo.tracker.ebook.TtsSynthesisPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChapterGeneratorTest {
    @Test fun resumesFromChunkCheckpointAndBuildsChapterWav() = runBlocking {
        val root = Files.createTempDirectory("tts-generation").toFile()
        val output = File(root, "output")
        val voice = TtsVoice("ru", "Russian", "ru", "model", "1", 0)
        val plan = TtsSynthesisPlan(
            documentFingerprint = "fingerprint",
            language = "ru",
            chapters = listOf(
                TtsChapterPlan(
                    chapterIndex = 0,
                    title = "Chapter",
                    chunks = listOf(
                        TtsPlannedChunk(0, 0, 0, "Chapter", "one", "fingerprint:0:0"),
                        TtsPlannedChunk(1, 0, 1, "Chapter", "two", "fingerprint:0:1"),
                    ),
                )
            ),
        )
        val provider = FakeProvider(voice).apply { failText = "two" }
        val generator = TtsChapterGenerator(provider, File(root, "work"))
        val initial = TtsSession("session", provider.id, voice, plan.documentFingerprint, 1f)

        val failed = generator.generate(plan, initial, output)
        assertEquals(TtsSessionState.FAILED, failed.state)
        assertEquals(1, failed.nextGlobalChunkIndex)
        assertEquals(listOf("one", "two"), provider.calls)

        provider.failText = null
        provider.calls.clear()
        val completed = generator.generate(plan, failed, output)
        assertEquals(TtsSessionState.COMPLETED, completed.state)
        assertEquals(2, completed.nextGlobalChunkIndex)
        assertTrue(0 in completed.completedChapterIndexes)
        assertEquals(listOf("two"), provider.calls)

        val chapter = File(output, "chapter-0000.wav")
        assertTrue(chapter.isFile)
        assertEquals(8, Pcm16Wav.info(chapter).dataSize)
    }

    private class FakeProvider(private val voice: TtsVoice) : TtsProvider {
        override val id: String = "fake"
        override val supportedLanguages: Set<String> = setOf("ru")
        var failText: String? = null
        val calls = mutableListOf<String>()

        override suspend fun getVoices(language: String): List<TtsVoice> = listOf(voice)

        override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
            calls += request.text
            if (request.text == failText) error("synthetic failure")
            val file = File(request.outputPath)
            Pcm16Wav.write(SherpaAudio(floatArrayOf(0.1f, -0.1f), 24000), file)
            return TtsSynthesisResult(file.absolutePath, 24000, 0)
        }
    }
}
