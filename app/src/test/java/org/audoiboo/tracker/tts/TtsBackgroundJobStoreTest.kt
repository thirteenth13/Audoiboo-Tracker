package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsBackgroundJobStoreTest {
    @Test fun roundTripPreservesBookModelAndChunkCount() {
        val root = Files.createTempDirectory("tts-background-job").toFile()
        val store = TtsBackgroundJobStore(root)
        val job = sampleJob(root, "job-1")
        store.save(job)
        assertEquals(job, store.load(job.sessionId))
        val metadata = metadataFile(root, job.sessionId)
        val json = JSONObject(metadata.readText(Charsets.UTF_8))
        assertFalse(json.has("document"))
        assertTrue(json.getString("documentFile").endsWith(".document.json"))
        assertEquals(1, documentFiles(root, job.sessionId).size)
    }

    @Test fun legacyJobWithoutChunkCountDerivesItFromDocument() {
        val root = Files.createTempDirectory("tts-background-job-legacy").toFile()
        val store = TtsBackgroundJobStore(root)
        val job = sampleJob(root, "legacy-job")
        store.save(job)
        val file = metadataFile(root, job.sessionId)
        val json = JSONObject(file.readText(Charsets.UTF_8))
        json.remove("chunkCount")
        file.writeText(json.toString(), Charsets.UTF_8)
        assertEquals(job, store.load(job.sessionId))
    }

    @Test fun replacingJobCleansObsoleteDocumentFile() {
        val root = Files.createTempDirectory("tts-background-job-replace").toFile()
        val store = TtsBackgroundJobStore(root)
        val initial = sampleJob(root, "replace-job")
        val updated = initial.copy(document = initial.document.copy(chapters = initial.document.chapters + BookChapter(2, "Third", listOf("New text"))))
        store.save(initial)
        val first = documentFiles(root, initial.sessionId).single()
        store.save(updated)
        assertEquals(updated, store.load(updated.sessionId))
        assertFalse(first.exists())
        assertEquals(1, documentFiles(root, updated.sessionId).size)
    }

    @Test fun deleteRemovesMetadataAndDocument() {
        val root = Files.createTempDirectory("tts-background-job-delete").toFile()
        val store = TtsBackgroundJobStore(root)
        val job = sampleJob(root, "delete-job")
        store.save(job)
        assertTrue(store.delete(job.sessionId))
        assertFalse(metadataFile(root, job.sessionId).exists())
        assertTrue(documentFiles(root, job.sessionId).isEmpty())
    }

    @Test fun previouslyAmbiguousSessionIdsDoNotOverwriteEachOther() {
        val root = Files.createTempDirectory("tts-background-job-collision").toFile()
        val store = TtsBackgroundJobStore(root)
        val colon = sampleJob(root, "a:b")
        val slash = sampleJob(root, "a/b")
        store.save(colon)
        store.save(slash)
        assertEquals(colon, store.load(colon.sessionId))
        assertEquals(slash, store.load(slash.sessionId))
        assertEquals(2, root.listFiles { f -> f.extension == "json" && !f.name.endsWith(".document.json") }?.size)
    }

    @Test fun corruptJsonIsIgnored() {
        val root = Files.createTempDirectory("tts-background-job-corrupt").toFile()
        metadataFile(root, "broken").writeText("{broken")
        assertNull(TtsBackgroundJobStore(root).load("broken"))
    }

    private fun metadataFile(root: File, sessionId: String) = File(root, TtsStableId.hex(sessionId) + ".json")
    private fun documentFiles(root: File, sessionId: String): List<File> {
        val prefix = TtsStableId.hex(sessionId) + "."
        return root.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".document.json") }?.toList().orEmpty()
    }

    private fun sampleJob(root: File, sessionId: String) = TtsBackgroundBookJob(
        sessionId = sessionId,
        document = BookDocument(
            title = "Book",
            authors = listOf("Author One", "Author Two"),
            language = "uk",
            series = "Series",
            seriesNumber = 3,
            chapters = listOf(
                BookChapter(0, "First", listOf("Paragraph 1", "Paragraph 2")),
                BookChapter(1, "Second", listOf("Text")),
            ),
        ),
        model = VoiceModelSpec("uk-model", "2", "uk", "a".repeat(64), "model.onnx"),
        outputDir = File(root, "output").absolutePath,
    )
}
