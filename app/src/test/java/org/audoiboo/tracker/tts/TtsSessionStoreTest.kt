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
        val session = sampleSession("book:42")

        store.save(session)

        assertEquals(session, store.load(session.sessionId))
        assertTrue(store.delete(session.sessionId))
        assertNull(store.load(session.sessionId))
    }

    @Test
    fun replacesExistingCheckpointWithoutLosingSession() {
        val root = Files.createTempDirectory("tts-session-replace").toFile()
        val store = TtsSessionStore(root)
        val initial = sampleSession("replace-me")
        val updated = initial.copy(
            state = TtsSessionState.PAUSED,
            nextGlobalChunkIndex = initial.nextGlobalChunkIndex + 3,
            completedChapterIndexes = initial.completedChapterIndexes + 7,
            lastError = null,
        )

        store.save(initial)
        store.save(updated)

        assertEquals(updated, store.load(updated.sessionId))
        assertEquals(1, root.listFiles { file -> file.extension == "properties" }?.size)
    }

    @Test
    fun pausedCheckpointSurvivesProcessRestart() {
        val root = Files.createTempDirectory("tts-session-pause-restart").toFile()
        val initial = sampleSession("pause-restart")
        val paused = initial.pause()

        TtsSessionStore(root).save(initial)
        TtsSessionStore(root).save(paused)

        val restored = TtsSessionStore(root).load(initial.sessionId)
        assertEquals(paused, restored)
        assertEquals(TtsSessionState.PAUSED, restored?.state)
        assertEquals(initial.nextGlobalChunkIndex, restored?.nextGlobalChunkIndex)
        assertEquals(initial.completedChapterIndexes, restored?.completedChapterIndexes)
        assertNull(restored?.lastError)
    }

    @Test
    fun previouslyAmbiguousSessionIdsDoNotOverwriteEachOther() {
        val root = Files.createTempDirectory("tts-session-collision").toFile()
        val store = TtsSessionStore(root)
        val colon = sampleSession("a:b")
        val slash = sampleSession("a/b")

        store.save(colon)
        store.save(slash)

        assertEquals(colon, store.load(colon.sessionId))
        assertEquals(slash, store.load(slash.sessionId))
        assertEquals(2, root.listFiles { file -> file.extension == "properties" }?.size)
    }

    @Test
    fun corruptCheckpointIsIgnored() {
        val root = Files.createTempDirectory("tts-session-corrupt").toFile()
        root.resolve(TtsStableId.hex("broken") + ".properties").writeText("not-a-valid-session")
        val store = TtsSessionStore(root)

        assertNull(store.load("broken"))
    }

    private fun sampleSession(sessionId: String) = TtsSession(
        sessionId = sessionId,
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
}
