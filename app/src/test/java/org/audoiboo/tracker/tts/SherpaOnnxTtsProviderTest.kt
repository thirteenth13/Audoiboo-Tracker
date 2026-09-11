package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxTtsProviderTest {
    @Test fun reusesAdapterForSameVoiceAndClosesIt() {
        val root = Files.createTempDirectory("tts-provider").toFile()
        val source = File(root, "source.bin").apply { writeText("model") }
        val spec = VoiceModelSpec("ru-model", "1", "ru", VoiceModelManager.digest(source), "model.bin")
        val manager = VoiceModelManager(root)
        manager.modelDir(spec).mkdirs()
        source.copyTo(manager.modelFile(spec), overwrite = true)
        val voice = TtsVoice("ru-1", "RU", "ru", spec.modelId, spec.version, 0)
        var created = 0
        var closed = 0
        val provider = SherpaOnnxTtsProvider(
            manager, mapOf(spec.modelId to spec), listOf(voice),
            SherpaAdapterFactory {
                created++
                object : SherpaOnnxAdapter {
                    override fun synthesize(text: String, speakerId: Int, speed: Float) = SherpaAudio(FloatArray(240), 24000)
                    override fun close() { closed++ }
                }
            },
            { _, file -> file.writeBytes(byteArrayOf(1, 2, 3)) },
        )
        kotlinx.coroutines.runBlocking {
            provider.synthesize(TtsSynthesisRequest("Один", "ru", voice, 1f, File(root, "a.wav").path))
            provider.synthesize(TtsSynthesisRequest("Два", "ru-RU", voice, 1f, File(root, "b.wav").path))
        }
        assertEquals(1, created)
        provider.close()
        assertEquals(1, closed)
    }

    @Test fun rejectsMissingOrCorruptModelBeforeInference() {
        val root = Files.createTempDirectory("tts-provider-missing").toFile()
        val spec = VoiceModelSpec("uk-model", "1", "uk", "0".repeat(64), "model.bin")
        val voice = TtsVoice("uk-1", "UK", "uk", spec.modelId, spec.version)
        var created = false
        val provider = SherpaOnnxTtsProvider(
            VoiceModelManager(root), mapOf(spec.modelId to spec), listOf(voice),
            SherpaAdapterFactory { created = true; error("must not create") },
            { _, _ -> },
        )
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                provider.synthesize(TtsSynthesisRequest("Текст", "uk", voice, 1f, File(root, "x.wav").path))
            }
        }
        assertTrue(result.isFailure)
        assertTrue(!created)
    }
}
