package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SupertonicAndroidAdapterTest {
    @Test fun detectsOnlyCompleteSupertonicRuntimeDirectory() {
        val dir = Files.createTempDirectory("supertonic-runtime").toFile()
        val primary = File(dir, SupertonicAndroidAdapter.DURATION_PREDICTOR).apply { writeText("x") }

        assertFalse(SupertonicAndroidAdapter.isModel(primary))

        SupertonicAndroidAdapter.REQUIRED_FILES
            .filterNot { it == SupertonicAndroidAdapter.DURATION_PREDICTOR }
            .forEach { name -> File(dir, name).writeText("x") }

        assertTrue(SupertonicAndroidAdapter.isModel(primary))
        assertFalse(SupertonicAndroidAdapter.isModel(File(dir, SupertonicAndroidAdapter.TEXT_ENCODER)))
    }

    @Test fun rejectsCompleteRuntimeWhenAnySiblingIsEmpty() {
        val dir = Files.createTempDirectory("supertonic-runtime-empty").toFile()
        SupertonicAndroidAdapter.REQUIRED_FILES.forEach { name -> File(dir, name).writeText("x") }
        val primary = File(dir, SupertonicAndroidAdapter.DURATION_PREDICTOR)

        assertTrue(SupertonicAndroidAdapter.isModel(primary))
        File(dir, SupertonicAndroidAdapter.VOICE_STYLE).writeBytes(ByteArray(0))
        assertFalse(SupertonicAndroidAdapter.isModel(primary))
    }

    @Test fun normalizesSupportedRegionalLanguageTags() {
        assertEquals("uk", SupertonicAndroidAdapter.normalizeLanguage("uk-UA"))
        assertEquals("ru", SupertonicAndroidAdapter.normalizeLanguage("ru-RU"))
        assertEquals("uk", SupertonicAndroidAdapter.normalizeLanguage(" UK_ua "))
    }

    @Test fun rejectsUnsupportedLanguageBeforeInference() {
        assertThrows(IllegalArgumentException::class.java) {
            SupertonicAndroidAdapter.normalizeLanguage("en-US")
        }
    }
}
