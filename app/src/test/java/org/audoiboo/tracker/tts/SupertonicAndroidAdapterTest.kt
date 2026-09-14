package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
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
}
