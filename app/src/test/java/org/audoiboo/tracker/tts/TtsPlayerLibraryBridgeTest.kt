package org.audoiboo.tracker.tts

import java.io.File
import java.nio.file.Files
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPlayerLibraryBridgeTest {
    @Test
    fun mapsCommittedChaptersToExistingPlayerLibraryShape() {
        val root = Files.createTempDirectory("tts-player-bridge").toFile()
        val first = File(root, "chapter-0000.wav").apply { writeBytes(ByteArray(64)) }
        val second = File(root, "chapter-0001.wav").apply { writeBytes(ByteArray(64)) }
        val document = BookDocument(
            title = "Книга: тест",
            authors = listOf("Автор Один", "Автор Два"),
            language = "uk",
            series = "Серія",
            seriesNumber = 2,
            chapters = listOf(
                BookChapter(0, "Початок", listOf("Текст")),
                BookChapter(1, "Далі", listOf("Текст")),
            ),
        )
        val voice = TtsVoice("uk", "UK", "uk", "model", "1")
        val session = TtsSession(
            sessionId = "bridge",
            providerId = "fake",
            voice = voice,
            documentFingerprint = "fingerprint",
            speed = 1f,
            state = TtsSessionState.COMPLETED,
            nextGlobalChunkIndex = 2,
            completedChapterIndexes = setOf(0, 1),
        )
        val result = TtsBookGenerationResult(
            session,
            listOf(
                TtsGeneratedChapter(1, "Далі", second),
                TtsGeneratedChapter(0, "Початок", first),
            ),
        )

        val items = TtsPlayerLibraryBridge.items(document, result)
        val fingerprint = TtsSynthesisPlanner.fingerprint(document).take(10)

        assertEquals(2, items.size)
        assertEquals(listOf("0001 - Початок.wav", "0002 - Далі.wav"), items.map { it.name })
        assertEquals(listOf("Книга: тест", "Книга: тест"), items.map { it.bookTitle })
        assertTrue(items.all { it.relativePath == "Audoiboo/TTS/Книга_ тест [$fingerprint]" })
        assertTrue(items.all { it.series == "Серія" })
        assertTrue(items.all { it.author == "Автор Один, Автор Два" })
        assertTrue(items.all { it.uri.startsWith("file:") })
    }

    @Test
    fun sameTitleWithDifferentContentGetsDifferentLibraryPath() {
        val first = BookDocument(
            title = "Однакова назва",
            authors = listOf("Автор"),
            language = "uk",
            series = null,
            seriesNumber = null,
            chapters = listOf(BookChapter(0, "Розділ", listOf("Перший текст"))),
        )
        val second = first.copy(
            chapters = listOf(BookChapter(0, "Розділ", listOf("Інший текст"))),
        )

        assertNotEquals(
            TtsPlayerLibraryBridge.relativePath(first),
            TtsPlayerLibraryBridge.relativePath(second),
        )
    }

    @Test
    fun ignoresMissingOrInvalidChapterFiles() {
        val root = Files.createTempDirectory("tts-player-bridge-invalid").toFile()
        val valid = File(root, "chapter-0000.wav").apply { writeBytes(ByteArray(64)) }
        val empty = File(root, "chapter-0001.wav").apply { writeBytes(ByteArray(44)) }
        val missing = File(root, "chapter-0002.wav")
        val document = BookDocument("Book", emptyList(), "en", null, null, emptyList())
        val voice = TtsVoice("en", "EN", "en", "model", "1")
        val session = TtsSession("bridge-2", "fake", voice, "fingerprint", 1f)
        val result = TtsBookGenerationResult(
            session,
            listOf(
                TtsGeneratedChapter(0, "One", valid),
                TtsGeneratedChapter(1, "Two", empty),
                TtsGeneratedChapter(2, "Three", missing),
            ),
        )

        val items = TtsPlayerLibraryBridge.items(document, result)

        assertEquals(1, items.size)
        assertEquals("0001 - One.wav", items.single().name)
    }
}
