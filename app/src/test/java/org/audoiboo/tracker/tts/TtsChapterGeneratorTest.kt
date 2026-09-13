package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.audoiboo.tracker.ebook.TtsChapterPlan
import org.audoiboo.tracker.ebook.TtsPlannedChunk
import org.audoiboo.tracker.ebook.TtsSynthesisPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChapterGeneratorTest {
    @Test fun resumesFromChunkCheckpointAndBuildsChapterWav() = runBlocking {
        val root = Files.createTempDirectory("tts-generation").toFile()
        val output = File(root, "output")
        val workRoot = File(root, "work")
        val sessionWork = File(workRoot, "session")
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
        val generator = TtsChapterGenerator(provider, workRoot)
        val initial = TtsSession("session", provider.id, voice, plan.documentFingerprint, 1f)

        val failed = generator.generate(plan, initial, output)
        assertEquals(TtsSessionState.FAILED, failed.state)
        assertEquals(1, failed.nextGlobalChunkIndex)
        assertEquals(listOf("one", "two"), provider.calls)
        assertTrue(sessionWork.isDirectory)

        provider.failText = null
        provider.calls.clear()
        val completed = generator.generate(plan, failed, output)
        assertEquals(TtsSessionState.COMPLETED, completed.state)
        assertEquals(2, completed.nextGlobalChunkIndex)
        assertTrue(0 in completed.completedChapterIndexes)
        assertEquals(listOf("two"), provider.calls)
        assertFalse(sessionWork.exists())

        val chapter = File(output, "chapter-0000.wav")
        assertTrue(chapter.isFile)
        assertEquals(8, Pcm16Wav.info(chapter).dataSize)
    }

    @Test fun skipsEmptyChapterWithoutClaimingAudioCompletion() = runBlocking {
        val root = Files.createTempDirectory("tts-empty-chapter").toFile()
        val voice = TtsVoice("ru", "Russian", "ru", "model", "1", 0)
        val provider = FakeProvider(voice)
        val plan = TtsSynthesisPlan(
            documentFingerprint = "fingerprint",
            language = "ru",
            chapters = listOf(
                TtsChapterPlan(0, "Empty", emptyList()),
                TtsChapterPlan(
                    1,
                    "Spoken",
                    listOf(TtsPlannedChunk(0, 1, 0, "Spoken", "text", "fingerprint:1:0")),
                ),
            ),
        )
        val session = TtsSession("session-empty", provider.id, voice, plan.documentFingerprint, 1f)
        val result = TtsChapterGenerator(provider, File(root, "work"))
            .generate(plan, session, File(root, "out"))

        assertEquals(TtsSessionState.COMPLETED, result.state)
        assertFalse(0 in result.completedChapterIndexes)
        assertTrue(1 in result.completedChapterIndexes)
        assertFalse(File(root, "out/chapter-0000.wav").exists())
        assertTrue(File(root, "out/chapter-0001.wav").isFile)
    }

    @Test fun failsPlanWithNoSynthesizableText() = runBlocking {
        val root = Files.createTempDirectory("tts-empty-plan").toFile()
        val voice = TtsVoice("ru", "Russian", "ru", "model", "1", 0)
        val provider = FakeProvider(voice)
        val plan = TtsSynthesisPlan(
            documentFingerprint = "fingerprint",
            language = "ru",
            chapters = listOf(TtsChapterPlan(0, "Empty", emptyList())),
        )
        val session = TtsSession("session-empty-plan", provider.id, voice, plan.documentFingerprint, 1f)
        val result = TtsChapterGenerator(provider, File(root, "work"))
            .generate(plan, session, File(root, "out"))

        assertEquals(TtsSessionState.FAILED, result.state)
        assertTrue(result.lastError.orEmpty().contains("no synthesizable text"))
        assertTrue(result.completedChapterIndexes.isEmpty())
        assertTrue(provider.calls.isEmpty())
    }

    @Test fun propagatesCancellationWithoutFailingSession() = runBlocking {
        val root = Files.createTempDirectory("tts-generation-cancel").toFile()
        val output = File(root, "output")
        val workRoot = File(root, "work")
        val sessionWork = File(workRoot, "session-cancel")
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
                    ),
                ),
            ),
        )
        val provider = object : TtsProvider {
            override val id = "fake"
            override val supportedLanguages = setOf("ru")
            override suspend fun getVoices(language: String): List<TtsVoice> = listOf(voice)
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
                throw CancellationException("cancelled")
            }
        }
        val initial = TtsSession("session-cancel", provider.id, voice, plan.documentFingerprint, 1f)
        val checkpoints = mutableListOf<TtsSession>()

        val error = runCatching {
            TtsChapterGenerator(provider, workRoot).generate(plan, initial, output, checkpoints::add)
        }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertTrue(checkpoints.none { it.state == TtsSessionState.FAILED })
        assertTrue(sessionWork.isDirectory)
    }

    @Test fun removesPartialTempChunkAfterProviderFailure() = runBlocking {
        val root = Files.createTempDirectory("tts-generation-partial").toFile()
        val output = File(root, "output")
        val workRoot = File(root, "work")
        val voice = TtsVoice("ru", "Russian", "ru", "model", "1", 0)
        val plan = TtsSynthesisPlan(
            documentFingerprint = "fingerprint",
            language = "ru",
            chapters = listOf(
                TtsChapterPlan(
                    chapterIndex = 0,
                    title = "Chapter",
                    chunks = listOf(TtsPlannedChunk(0, 0, 0, "Chapter", "broken", "fingerprint:0:0")),
                ),
            ),
        )
        val provider = object : TtsProvider {
            override val id = "fake"
            override val supportedLanguages = setOf("ru")
            override suspend fun getVoices(language: String): List<TtsVoice> = listOf(voice)
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
                File(request.outputPath).writeBytes(byteArrayOf(1, 2, 3, 4))
                error("provider failed after partial write")
            }
        }
        val initial = TtsSession("session-partial", provider.id, voice, plan.documentFingerprint, 1f)
        val result = TtsChapterGenerator(provider, workRoot).generate(plan, initial, output)

        assertEquals(TtsSessionState.FAILED, result.state)
        val chapterWork = File(workRoot, "session-partial/chapter-0")
        assertTrue(chapterWork.isDirectory)
        assertFalse(File(chapterWork, "chunk-000000.wav.tmp").exists())
        assertFalse(File(chapterWork, "chunk-000000.wav").exists())
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
