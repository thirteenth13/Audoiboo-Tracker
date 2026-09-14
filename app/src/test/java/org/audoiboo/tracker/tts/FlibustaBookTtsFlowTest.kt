package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.audoiboo.tracker.plugin.flibusta.FlibustaFailureCode
import org.audoiboo.tracker.plugin.flibusta.FlibustaPayloadKind
import org.audoiboo.tracker.plugin.flibusta.FlibustaResolveResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlibustaBookTtsFlowTest {
    @Test
    fun `resolved FB2 is imported and passed to Sherpa preparation`() {
        var preparedLanguage: String? = null
        var preparedTitle: String? = null
        val flow = FlibustaBookTtsFlow(
            resolveFb2 = {
                FlibustaResolveResult.Success(
                    bytes = validFb2("uk"),
                    finalUrl = "https://flibusta.site/b/42/fb2",
                    kind = FlibustaPayloadKind.RAW_FB2,
                    contentType = "application/xml",
                    fileName = "42.fb2",
                )
            },
            prepareTts = { document, speed ->
                preparedLanguage = document.language
                preparedTitle = document.title
                Result.success(prepared(document.language ?: "uk", speed))
            },
        )

        val result = flow.prepare("https://flibusta.site/b/42", speed = 1.15f).getOrThrow()

        assertEquals("uk", preparedLanguage)
        assertEquals("Тестова книга", preparedTitle)
        assertEquals("42.fb2", result.fileName)
        assertEquals("https://flibusta.site/b/42/fb2", result.sourceUrl)
        assertEquals(1.15f, result.tts.session.speed)
        assertEquals(1, result.tts.chunkCount)
        assertEquals("Перший розділ", result.document.chapters.single().title)
    }

    @Test
    fun `Flibusta FB2 can synthesize into player library items`() = runBlocking {
        val flow = FlibustaBookTtsFlow(
            resolveFb2 = {
                FlibustaResolveResult.Success(
                    bytes = validFb2("uk"),
                    finalUrl = "https://flibusta.site/b/42/fb2",
                    kind = FlibustaPayloadKind.RAW_FB2,
                    contentType = "application/xml",
                    fileName = "42.fb2",
                )
            },
            prepareTts = { document, speed -> Result.success(prepared(document, speed)) },
        )
        val prepared = flow.prepare("https://flibusta.site/b/42", speed = 1.05f).getOrThrow()
        val root = Files.createTempDirectory("flibusta-tts-e2e").toFile()
        val provider = object : TtsProvider {
            override val id = "sherpa-onnx"
            override val supportedLanguages = setOf("uk")
            override suspend fun getVoices(language: String) = listOf(prepared.tts.voice)
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult {
                val file = File(request.outputPath)
                Pcm16Wav.write(SherpaAudio(FloatArray(2400) { 0.1f }, 24000), file)
                return TtsSynthesisResult(file.absolutePath, 24000, 100)
            }
        }
        val generator = TtsBookGenerator(TtsChapterGenerator(provider, File(root, "work")))

        val generated = generator.generate(
            document = prepared.document,
            initialSession = prepared.tts.session,
            outputDir = File(root, "out"),
        )
        val items = TtsPlayerLibraryBridge.items(prepared.document, generated)

        assertEquals(TtsSessionState.COMPLETED, generated.session.state)
        assertEquals(1, generated.chapters.size)
        assertTrue(generated.chapters.single().audioFile.isFile)
        assertTrue(generated.chapters.single().audioFile.length() > 44L)
        assertEquals(1, items.size)
        assertEquals("Тестова книга", items.single().bookTitle)
        assertEquals("Іван Автор", items.single().author)
        assertTrue(items.single().relativePath.startsWith("Audoiboo/TTS/"))
        assertTrue(items.single().name.contains("Перший розділ"))
    }

    @Test
    fun `different sessions never share physical chapter output directory`() {
        val root = File("tts-output")
        val first = FlibustaBookTtsFlow.outputDirectory(root, "session-a")
        val second = FlibustaBookTtsFlow.outputDirectory(root, "session-b")

        assertNotEquals(first.path, second.path)
        assertEquals(File(root, TtsStableId.hex("session-a")).path, first.path)
        assertTrue(first.path.startsWith(root.path))
    }

    @Test
    fun `resolver failure stops before TTS preparation`() {
        var prepareCalled = false
        val flow = FlibustaBookTtsFlow(
            resolveFb2 = {
                FlibustaResolveResult.Failure(
                    code = FlibustaFailureCode.NOT_FOUND,
                    message = "HTTP 404",
                    httpStatus = 404,
                )
            },
            prepareTts = { _, _ ->
                prepareCalled = true
                error("must not run")
            },
        )

        val result = flow.prepare("https://flibusta.site/b/404")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("NOT_FOUND"))
        assertTrue(!prepareCalled)
    }

    @Test
    fun `invalid downloaded payload stops before TTS preparation`() {
        var prepareCalled = false
        val flow = FlibustaBookTtsFlow(
            resolveFb2 = {
                FlibustaResolveResult.Success(
                    bytes = "not an fb2 document".toByteArray(),
                    finalUrl = "https://flibusta.one/b/7/fb2",
                    kind = FlibustaPayloadKind.RAW_FB2,
                    contentType = "application/xml",
                    fileName = "7.fb2",
                )
            },
            prepareTts = { _, _ ->
                prepareCalled = true
                error("must not run")
            },
        )

        val result = flow.prepare("https://flibusta.one/b/7")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("valid FB2"))
        assertTrue(!prepareCalled)
    }

    private fun prepared(language: String, speed: Float): PreparedSherpaBookTts {
        val model = VoiceModelSpec(
            modelId = "test-$language",
            version = "v1",
            language = language,
            sha256 = "1".repeat(64),
            fileName = "model.int8.onnx",
        )
        val voice = TtsVoice(
            id = "voice-$language",
            displayName = "Test voice",
            language = language,
            modelId = model.modelId,
            modelVersion = model.version,
            speakerId = 0,
        )
        val session = TtsSession(
            sessionId = "session-1",
            providerId = "sherpa-onnx",
            voice = voice,
            documentFingerprint = "f".repeat(64),
            speed = speed,
        )
        return PreparedSherpaBookTts(model, voice, session, chunkCount = 1)
    }

    private fun prepared(document: BookDocument, speed: Float): PreparedSherpaBookTts {
        val language = document.language ?: "uk"
        val model = VoiceModelSpec(
            modelId = "test-$language",
            version = "v1",
            language = language,
            sha256 = "1".repeat(64),
            fileName = "model.int8.onnx",
        )
        val voice = TtsVoice(
            id = "voice-$language",
            displayName = "Test voice",
            language = language,
            modelId = model.modelId,
            modelVersion = model.version,
            speakerId = 0,
        )
        val plan = TtsSynthesisPlanner.build(document)
        val session = TtsSession(
            sessionId = "session-e2e",
            providerId = "sherpa-onnx",
            voice = voice,
            documentFingerprint = plan.documentFingerprint,
            speed = speed,
        )
        return PreparedSherpaBookTts(model, voice, session, chunkCount = plan.chunkCount)
    }

    private fun validFb2(language: String): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
          <description>
            <title-info>
              <book-title>Тестова книга</book-title>
              <lang>$language</lang>
              <author><first-name>Іван</first-name><last-name>Автор</last-name></author>
            </title-info>
          </description>
          <body>
            <section>
              <title><p>Перший розділ</p></title>
              <p>Це текст для синтезу мовлення.</p>
            </section>
          </body>
        </FictionBook>
    """.trimIndent().toByteArray()
}
