package org.audoiboo.tracker.tts

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSessionStoreTest {
    @Test
    fun roundTripsCheckpointAndDeletesIt() {
        val root = Files.createTempDirectory("tts-session-store").toFile()
        val store = TtsSessionStore(root)
        val session = TtsSession(
            sessionId = "book:42",
            providerId = "sherpa-onnx",
            voice = TtsVoice(
                id = "uk-voice",
                displayName = "Український голос",
                language = "uk-UA",
                modelId = "vits-uk",
                modelVersion = "2",
                speakerId = 3,
            ),
            documentFingerprint = "fingerprint",
            speed = 1.25f,
            state = TtsSessionState.RUNNING,
            nextGlobalChunkIndex = 17,
            completedChapterIndexes = setOf(0, 2, 5),
            lastError = "помилка",
        )

        store.save(session)

        assertEquals(session, store.load(session.sessionId))
        assertTrue(store.delete(session.sessionId))
        assertNull(store.load(session.sessionId))
    }

    @Test
    fun corruptCheckpointIsIgnored() {
        val root = Files.createTempDirectory("tts-session-corrupt").toFile()
        root.resolve("broken.properties").writeText("not-a-valid-session")
        val store = TtsSessionStore(root)

        assertNull(store.load("broken"))
    }
}
