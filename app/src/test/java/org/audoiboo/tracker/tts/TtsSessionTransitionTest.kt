package org.audoiboo.tracker.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TtsSessionTransitionTest {
    private fun session(state: TtsSessionState, error: String? = null) = TtsSession(
        sessionId = "resume-session",
        providerId = "sherpa-onnx",
        voice = TtsVoice("uk", "UK", "uk", "model", "v1"),
        documentFingerprint = "fingerprint",
        speed = 1f,
        state = state,
        nextGlobalChunkIndex = 7,
        completedChapterIndexes = setOf(0, 1),
        lastError = error,
    )

    @Test
    fun pausePreservesCheckpointAndClearsError() {
        val paused = session(TtsSessionState.RUNNING, "old error").pause()

        assertEquals(TtsSessionState.PAUSED, paused.state)
        assertEquals(7, paused.nextGlobalChunkIndex)
        assertEquals(setOf(0, 1), paused.completedChapterIndexes)
        assertNull(paused.lastError)
    }

    @Test
    fun failedSessionCanBeQueuedForResumeWithoutLosingProgress() {
        val queued = session(TtsSessionState.FAILED, "synthesis failed").queueForResume()

        assertEquals(TtsSessionState.QUEUED, queued.state)
        assertEquals(7, queued.nextGlobalChunkIndex)
        assertEquals(setOf(0, 1), queued.completedChapterIndexes)
        assertNull(queued.lastError)
    }

    @Test
    fun completedSessionCannotBeQueuedAgain() {
        assertThrows(IllegalArgumentException::class.java) {
            session(TtsSessionState.COMPLETED).queueForResume()
        }
    }
}
