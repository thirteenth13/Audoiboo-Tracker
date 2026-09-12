package org.audoiboo.tracker.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaVoiceCatalogTest {
    @Test
    fun `catalog selects pinned Ukrainian and Russian packages`() {
        assertEquals(SherpaVoiceCatalog.ukrainian, SherpaVoiceCatalog.forLanguage("uk-UA"))
        assertEquals(SherpaVoiceCatalog.russian, SherpaVoiceCatalog.forLanguage("ru_RU"))
        assertNull(SherpaVoiceCatalog.forLanguage("en"))
    }

    @Test
    fun `catalog assets are pinned and mobile sized`() {
        SherpaVoiceCatalog.all.forEach { voice ->
            assertTrue(voice.archiveUrl.contains("/releases/download/tts-models/"))
            assertTrue(voice.archiveSha256.matches(Regex("[0-9a-f]{64}")))
            assertTrue(voice.archiveSizeBytes in 1L..30_000_000L)
            assertTrue(voice.modelFileName.endsWith(".int8.onnx"))
        }
    }
}
