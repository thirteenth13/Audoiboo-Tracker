package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModelManagerTest {
    @Test fun verifiesInstalledModelBySha256() {
        val root = Files.createTempDirectory("voice-models").toFile()
        try {
            val bytes = "model-data".toByteArray()
            val temp = File(root, "source.bin").apply { writeBytes(bytes) }
            val hash = VoiceModelManager.digest(temp)
            val spec = VoiceModelSpec("ru-test", "1", "ru", hash, "model.onnx")
            val manager = VoiceModelManager(root)
            manager.modelDir(spec).mkdirs()
            manager.modelFile(spec).writeBytes(bytes)

            assertTrue(manager.isInstalled(spec))
            assertTrue(manager.verify(spec).isSuccess)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsChecksumMismatch() {
        val root = Files.createTempDirectory("voice-models").toFile()
        try {
            val spec = VoiceModelSpec("uk-test", "1", "uk", "0".repeat(64), "model.onnx")
            val manager = VoiceModelManager(root)
            manager.modelDir(spec).mkdirs()
            manager.modelFile(spec).writeText("different")

            assertFalse(manager.isInstalled(spec))
            assertTrue(manager.verify(spec).isFailure)
        } finally {
            root.deleteRecursively()
        }
    }
}
