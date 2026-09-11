package org.audoiboo.tracker.tts

import java.io.File
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
        assertEquals("Перший розділ", result.document.chapters.single().title)
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
        return PreparedSherpaBookTts(model, voice, session)
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
