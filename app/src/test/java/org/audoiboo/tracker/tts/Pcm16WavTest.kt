package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16WavTest {
    @Test fun writesValidMonoPcm16Header() {
        val file = File(Files.createTempDirectory("wav").toFile(), "sample.wav")
        Pcm16Wav.write(SherpaAudio(floatArrayOf(-1f, 0f, 1f), 24000), file)
        val info = Pcm16Wav.info(file)
        assertEquals(24000, info.sampleRateHz)
        assertEquals(6, info.dataSize)
        assertEquals(50L, file.length())
    }

    @Test fun appendsChunksAndUpdatesHeader() {
        val dir = Files.createTempDirectory("wav-append").toFile()
        val first = File(dir, "first.wav")
        val second = File(dir, "second.wav")
        val chapter = File(dir, "chapter.wav")
        Pcm16Wav.write(SherpaAudio(FloatArray(100) { 0.1f }, 22050), first)
        Pcm16Wav.write(SherpaAudio(FloatArray(50) { -0.1f }, 22050), second)
        Pcm16Wav.append(first, chapter)
        Pcm16Wav.append(second, chapter)
        val info = Pcm16Wav.info(chapter)
        assertEquals(300, info.dataSize)
        assertEquals(344L, chapter.length())
        assertTrue(chapter.isFile)
    }

    @Test fun rejectsDifferentSampleRates() {
        val dir = Files.createTempDirectory("wav-rate").toFile()
        val first = File(dir, "first.wav")
        val second = File(dir, "second.wav")
        val chapter = File(dir, "chapter.wav")
        Pcm16Wav.write(SherpaAudio(FloatArray(10), 16000), first)
        Pcm16Wav.write(SherpaAudio(FloatArray(10), 24000), second)
        Pcm16Wav.append(first, chapter)
        assertTrue(runCatching { Pcm16Wav.append(second, chapter) }.isFailure)
    }
}
