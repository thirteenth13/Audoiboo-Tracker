package org.audoiboo.tracker.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaVoiceCatalogTest {
    @Test
    fun `catalog selects pinned Ukrainian and Russian fast packages`() {
        assertEquals(SherpaVoiceCatalog.ukrainian, SherpaVoiceCatalog.forLanguage("uk-UA"))
        assertEquals(SherpaVoiceCatalog.russian, SherpaVoiceCatalog.forLanguage("ru_RU"))
        assertNull(SherpaVoiceCatalog.forLanguage("en"))
    }

    @Test
    fun `fast catalog assets are pinned and mobile sized`() {
        SherpaVoiceCatalog.all.forEach { voice ->
            assertTrue(voice.archiveUrl.contains("/releases/download/tts-models/"))
            assertTrue(voice.archiveSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(voice.archiveSizeBytes in 1L..30_000_000L)
            assertTrue(voice.modelFileName.endsWith(".int8.onnx"))
            assertEquals(TtsEngineFamily.PIPER_VITS, voice.engineFamily)
        }
    }

    @Test
    fun `high quality RU and UA share exact pinned Supertonic package`() {
        val uk = SherpaVoiceCatalog.forLanguage("uk-UA", TtsQuality.HIGH_QUALITY)!!
        val ru = SherpaVoiceCatalog.forLanguage("ru_RU", TtsQuality.HIGH_QUALITY)!!

        assertEquals("uk", uk.language)
        assertEquals("ru", ru.language)
        assertEquals(TtsEngineFamily.SUPERTONIC, uk.engineFamily)
        assertEquals(TtsEngineFamily.SUPERTONIC, ru.engineFamily)
        assertEquals(uk.modelId, ru.modelId)
        assertEquals(uk.version, ru.version)
        assertEquals(uk.archiveUrl, ru.archiveUrl)
        assertEquals(uk.archiveSha256, ru.archiveSha256)
        assertEquals(uk.archiveSizeBytes, ru.archiveSizeBytes)
        assertEquals(128_774_318L, uk.archiveSizeBytes)
        assertEquals("82fa96f91c4ef8abaae3a14a3f4153facf88bed821d1f7331cec2700f432c427", uk.archiveSha256)
        assertNull(SherpaVoiceCatalog.forLanguage("en", TtsQuality.HIGH_QUALITY))
    }
}
