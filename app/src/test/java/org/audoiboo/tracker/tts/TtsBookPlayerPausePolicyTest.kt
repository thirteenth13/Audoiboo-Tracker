package org.audoiboo.tracker.tts

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsBookPlayerPausePolicyTest {
    private val voice = TtsVoice("uk-test", "UK Test", "uk", "model", "1")
    private val base = TtsSession(
        sessionId = "pause-race",
        providerId = "fake",
        voice = voice,
        documentFingerprint = "f".repeat(64),
        speed = 1f,
    )

    private val provider = object : TtsProvider {
        override val id = "fake"
        override val supportedLanguages = setOf("uk")
        override suspend fun getVoices(language: String) = emptyList<TtsVoice>()
        override suspend fun synthesize(request: TtsSynthesisRequest): TtsSynthesisResult =
            error("not used")
    }

    private fun generator() = TtsBookPlayerGenerator(
        TtsBookGenerator(TtsChapterGenerator(provider, File("build/test-tts-work"))),
        { _, _ -> },
    )

    @Test
    fun pausedCheckpointWinsOverLateRunningCheckpoint() {
        val persisted = base.copy(
            state = TtsSessionState.PAUSED,
            nextGlobalChunkIndex = 3,
        )
        val lateWorker = base.copy(
            state = TtsSessionState.RUNNING,
            nextGlobalChunkIndex = 4,
            lastError = "stale",
        )

        val merged = generator().preservePause(persisted, lateWorker)

        assertEquals(TtsSessionState.PAUSED, merged.state)
        assertEquals(4, merged.nextGlobalChunkIndex)
        assertNull(merged.lastError)
    }

    @Test
    fun normalCheckpointRemainsUnchangedWithoutPause() {
        val running = base.copy(state = TtsSessionState.RUNNING, nextGlobalChunkIndex = 2)

        val merged = generator().preservePause(base, running)

        assertEquals(running, merged)
    }
}
