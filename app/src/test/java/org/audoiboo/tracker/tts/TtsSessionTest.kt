package org.audoiboo.tracker.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSessionTest {
    private val voice = TtsVoice(
        id = "ru-test",
        displayName = "Test voice",
        language = "ru",
        modelId = "model-a",
        modelVersion = "1",
        speakerId = 2,
    )

    @Test fun voiceKeyIsStable() {
        assertEquals("ru-test:ru:model-a:1:2", voice.stableKey)
    }

    @Test fun providerLanguageMatchingAcceptsRegionalCodes() {
        val provider = object : TtsProvider {
            override val id = "test"
            override val supportedLanguages = setOf("ru", "uk-UA")
            override suspend fun getVoices(language: String) = emptyList<TtsVoice>()
            override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult =
                TtsSynthesisResult(request.outputPath, 24000, 1)
        }
        assertTrue(provider.supportsLanguage("ru-RU"))
        assertTrue(provider.supportsLanguage("uk"))
        assertFalse(provider.supportsLanguage("en"))
    }

    @Test fun sessionCheckpointTracksProgress() {
        val session = TtsSession(
            sessionId = "job-1",
            providerId = "sherpa-onnx",
            voice = voice,
            documentFingerprint = "abc123",
            speed = 1.0f,
        ).advance(nextChunkIndex = 5, completedChapterIndex = 0)

        assertEquals(TtsSessionState.RUNNING, session.state)
        assertEquals(5, session.checkpoint().nextGlobalChunkIndex)
        assertEquals(setOf(0), session.checkpoint().completedChapterIndexes)
        assertEquals(voice.stableKey, session.checkpoint().voiceStableKey)
    }

    @Test fun failedSessionKeepsProgressForResume() {
        val session = TtsSession(
            sessionId = "job-2",
            providerId = "sherpa-onnx",
            voice = voice,
            documentFingerprint = "abc123",
            speed = 1.1f,
            nextGlobalChunkIndex = 7,
        ).fail("thermal pause")

        assertEquals(TtsSessionState.FAILED, session.state)
        assertEquals(7, session.nextGlobalChunkIndex)
        assertEquals("thermal pause", session.lastError)
    }
}
