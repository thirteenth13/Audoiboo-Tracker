package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsBackgroundJobStoreTest {
    @Test
    fun roundTripPreservesBookAndModel() {
        val root = Files.createTempDirectory("tts-background-job").toFile()
        val store = TtsBackgroundJobStore(root)
        val job = sampleJob(root, "job-1")

        store.save(job)
        val loaded = store.load(job.sessionId)

        assertEquals(job, loaded)
    }

    @Test
    fun previouslyAmbiguousSessionIdsDoNotOverwriteEachOther() {
        val root = Files.createTempDirectory("tts-background-job-collision").toFile()
        val store = TtsBackgroundJobStore(root)
        val colon = sampleJob(root, "a:b")
        val slash = sampleJob(root, "a/b")

        store.save(colon)
        store.save(slash)

        assertEquals(colon, store.load(colon.sessionId))
        assertEquals(slash, store.load(slash.sessionId))
        assertEquals(2, root.listFiles { file -> file.extension == "json" }?.size)
    }

    @Test
    fun corruptJsonIsIgnored() {
        val root = Files.createTempDirectory("tts-background-job-corrupt").toFile()
        File(root, TtsStableId.hex("broken") + ".json").writeText("{broken")

        assertNull(TtsBackgroundJobStore(root).load("broken"))
    }

    private fun sampleJob(root: File, sessionId: String) = TtsBackgroundBookJob(
        sessionId = sessionId,
        document = BookDocument(
            title = "Книга",
            authors = listOf("Автор Один", "Автор Два"),
            language = "uk",
            series = "Серія",
            seriesNumber = 3,
            chapters = listOf(
                BookChapter(0, "Перша", listOf("Абзац 1", "Абзац 2")),
                BookChapter(1, "Друга", listOf("Текст")),
            ),
        ),
        model = VoiceModelSpec(
            modelId = "uk-model",
            version = "2",
            language = "uk",
            sha256 = "a".repeat(64),
            fileName = "model.onnx",
        ),
        outputDir = File(root, "output").absolutePath,
    )
}
