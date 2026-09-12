package org.audoiboo.tracker.tts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.audoiboo.tracker.ebook.BookChapter
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaBookTtsCoordinatorTest {
    @Test
    fun preparesVerifiedUkrainianBookForBackgroundSherpa() {
        val root = Files.createTempDirectory("sherpa-book").toFile()
        try {
            val archive = archiveOf(
                "vits-piper-test/model.int8.onnx" to byteArrayOf(1, 2, 3),
                "vits-piper-test/tokens.txt" to "a\nb\n".toByteArray(),
                "vits-piper-test/espeak-ng-data/readme" to "data".toByteArray(),
            )
            val archiveFile = Files.createTempFile("sherpa-book", ".tar.bz2").toFile()
            val sha = try {
                archiveFile.writeBytes(archive)
                VoiceModelManager.digest(archiveFile)
            } finally {
                archiveFile.delete()
            }
            val pkg = SherpaVoicePackage(
                modelId = "test-uk",
                version = "test-v1",
                language = "uk",
                displayName = "Тестовий голос",
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/test.tar.bz2",
                archiveSha256 = sha,
                archiveSizeBytes = archive.size.toLong(),
                modelFileName = "model.int8.onnx",
            )
            val manager = VoiceModelManager(root)
            var downloads = 0
            val installer = SherpaVoiceInstaller(manager) {
                downloads++
                ByteArrayInputStream(archive)
            }
            val coordinator = SherpaBookTtsCoordinator(
                installer = installer,
                packageResolver = { language -> pkg.takeIf { language.startsWith("uk", ignoreCase = true) } },
                sessionIdFactory = { "session-1" },
            )
            val document = BookDocument(
                title = "Книга",
                authors = listOf("Автор"),
                language = "uk-UA",
                series = null,
                seriesNumber = null,
                chapters = listOf(BookChapter(0, "Розділ", listOf("Це тестовий текст для озвучення."))),
            )

            val prepared = coordinator.prepare(document, speed = 1.1f).getOrThrow()
            val preparedAgain = coordinator.prepare(document, speed = 1.1f).getOrThrow()

            assertEquals(1, downloads)
            assertEquals("sherpa-onnx", prepared.session.providerId)
            assertEquals("session-1", prepared.session.sessionId)
            assertEquals(TtsSessionState.QUEUED, prepared.session.state)
            assertEquals(1.1f, prepared.session.speed)
            assertEquals(pkg.modelId, prepared.voice.modelId)
            assertEquals(pkg.version, prepared.voice.modelVersion)
            assertEquals("uk", prepared.voice.language)
            assertEquals(TtsSynthesisPlanner.fingerprint(document), prepared.session.documentFingerprint)
            assertTrue(manager.isInstalled(prepared.model))
            assertEquals(prepared.model.sha256, preparedAgain.model.sha256)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsupportedBookLanguageBeforeDownload() {
        val root = Files.createTempDirectory("sherpa-book-lang").toFile()
        try {
            var downloads = 0
            val coordinator = SherpaBookTtsCoordinator(
                installer = SherpaVoiceInstaller(VoiceModelManager(root)) {
                    downloads++
                    ByteArrayInputStream(byteArrayOf())
                },
                packageResolver = { null },
                sessionIdFactory = { "session-2" },
            )
            val document = BookDocument(
                title = "Book",
                authors = emptyList(),
                language = "en",
                series = null,
                seriesNumber = null,
                chapters = listOf(BookChapter(0, "Chapter", listOf("Text"))),
            )

            assertTrue(coordinator.prepare(document).isFailure)
            assertEquals(0, downloads)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archiveOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                for ((name, content) in files) {
                    val entry = TarArchiveEntry(name)
                    entry.size = content.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return bytes.toByteArray()
    }
}
