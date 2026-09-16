package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsModelStorageTest {
    @Test fun modelsLiveUnderAudoibooDownloadDirectory() {
        val downloads = Files.createTempDirectory("downloads").toFile()

        val root = TtsModelStorage.root(downloads)

        assertEquals(File(downloads, "Audoiboo/models"), root)
    }
}
