package org.audoiboo.tracker.tts

import java.nio.file.Files
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSessionStoreTest {
    @Test fun roundTripsCheckpointAndDeletesIt() {
        val root = Files.createTempDirectory("tts-session-store").toFile(); val store = TtsSessionStore(root); val session = sampleSession("book:42")
        store.save(session); assertEquals(session, store.load(session.sessionId)); assertTrue(store.delete(session.sessionId)); assertNull(store.load(session.sessionId))
    }

    @Test fun `round trips Supertonic quality and engine family`() {
        val root = Files.createTempDirectory("tts-session-supertonic").toFile(); val store = TtsSessionStore(root)
        val session = sampleSession("hq").copy(quality = TtsQuality.HIGH_QUALITY, engineFamily = TtsEngineFamily.SUPERTONIC)
        store.save(session)
        val restored = store.load(session.sessionId)
        assertEquals(TtsQuality.HIGH_QUALITY, restored?.quality)
        assertEquals(TtsEngineFamily.SUPERTONIC, restored?.engineFamily)
        assertEquals(session, restored)
    }

    @Test fun `version 1 checkpoint defaults to fast Piper`() {
        val root = Files.createTempDirectory("tts-session-v1").toFile(); val sessionId = "legacy-v1"
        val props = Properties().apply {
            setProperty("version", "1"); setProperty("sessionId", sessionId); setProperty("providerId", "sherpa-onnx")
            setProperty("voice.id", "legacy"); setProperty("voice.displayName", "Legacy voice"); setProperty("voice.language", "uk")
            setProperty("voice.modelId", "vits-uk"); setProperty("voice.modelVersion", "1"); setProperty("documentFingerprint", "fingerprint")
            setProperty("speed", "1.0"); setProperty("state", "PAUSED"); setProperty("nextGlobalChunkIndex", "3"); setProperty("completedChapterIndexes", "0,1")
        }
        root.resolve(TtsStableId.hex(sessionId) + ".properties").outputStream().use { props.store(it, null) }
        val restored = TtsSessionStore(root).load(sessionId)
        assertEquals(TtsQuality.FAST, restored?.quality)
        assertEquals(TtsEngineFamily.PIPER_VITS, restored?.engineFamily)
        assertEquals(TtsSessionState.PAUSED, restored?.state)
        assertEquals(3, restored?.nextGlobalChunkIndex)
    }

    @Test fun replacesExistingCheckpointWithoutLosingSession() {
        val root = Files.createTempDirectory("tts-session-replace").toFile(); val store = TtsSessionStore(root); val initial = sampleSession("replace-me")
        val updated = initial.copy(state = TtsSessionState.PAUSED, nextGlobalChunkIndex = initial.nextGlobalChunkIndex + 3, completedChapterIndexes = initial.completedChapterIndexes + 7, lastError = null)
        store.save(initial); store.save(updated); assertEquals(updated, store.load(updated.sessionId)); assertEquals(1, root.listFiles { file -> file.extension == "properties" }?.size)
    }

    @Test fun pausedCheckpointSurvivesProcessRestart() {
        val root = Files.createTempDirectory("tts-session-pause-restart").toFile(); val initial = sampleSession("pause-restart"); val paused = initial.pause()
        TtsSessionStore(root).save(initial); TtsSessionStore(root).save(paused); val restored = TtsSessionStore(root).load(initial.sessionId)
        assertEquals(paused, restored); assertEquals(TtsSessionState.PAUSED, restored?.state); assertEquals(initial.nextGlobalChunkIndex, restored?.nextGlobalChunkIndex); assertNull(restored?.lastError)
    }

    @Test fun previouslyAmbiguousSessionIdsDoNotOverwriteEachOther() {
        val root = Files.createTempDirectory("tts-session-collision").toFile(); val store = TtsSessionStore(root); val colon = sampleSession("a:b"); val slash = sampleSession("a/b")
        store.save(colon); store.save(slash); assertEquals(colon, store.load(colon.sessionId)); assertEquals(slash, store.load(slash.sessionId)); assertEquals(2, root.listFiles { file -> file.extension == "properties" }?.size)
    }

    @Test fun corruptCheckpointIsIgnored() {
        val root = Files.createTempDirectory("tts-session-corrupt").toFile(); root.resolve(TtsStableId.hex("broken") + ".properties").writeText("not-a-valid-session")
        assertNull(TtsSessionStore(root).load("broken"))
    }

    private fun sampleSession(sessionId: String) = TtsSession(
        sessionId = sessionId, providerId = "sherpa-onnx",
        voice = TtsVoice("uk-voice", "Український голос", "uk-UA", "vits-uk", "2", 3),
        documentFingerprint = "fingerprint", speed = 1.25f, state = TtsSessionState.RUNNING,
        nextGlobalChunkIndex = 17, completedChapterIndexes = setOf(0, 2, 5), lastError = "помилка",
    )
}
