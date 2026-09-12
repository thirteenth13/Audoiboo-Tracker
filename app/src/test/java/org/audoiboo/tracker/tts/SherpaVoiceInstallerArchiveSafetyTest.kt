package org.audoiboo.tracker.tts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaVoiceInstallerArchiveSafetyTest {
    @Test
    fun rejectsAbsoluteArchivePath() {
        assertRejected(
            archive {
                file("/absolute.txt", byteArrayOf(1))
                runtimeFiles()
            }
        )
    }

    @Test
    fun rejectsSymbolicLinkEntry() {
        assertRejected(
            archive {
                link("vits-piper-test/link", "../../outside", TarConstants.LF_SYMLINK)
                runtimeFiles()
            }
        )
    }

    @Test
    fun rejectsHardLinkEntry() {
        assertRejected(
            archive {
                link("vits-piper-test/hard", "vits-piper-test/model.int8.onnx", TarConstants.LF_LINK)
                runtimeFiles()
            }
        )
    }

    private fun assertRejected(archive: ByteArray) {
        val root = Files.createTempDirectory("voice-archive-safety").toFile()
        try {
            val archiveFile = Files.createTempFile("voice", ".tar.bz2").toFile()
            archiveFile.writeBytes(archive)
            val pkg = SherpaVoicePackage(
                modelId = "test-model",
                version = "test-v1",
                language = "uk",
                displayName = "Test voice",
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/test.tar.bz2",
                archiveSha256 = VoiceModelManager.digest(archiveFile),
                archiveSizeBytes = archive.size.toLong(),
                modelFileName = "model.int8.onnx",
            )
            archiveFile.delete()

            val manager = VoiceModelManager(root)
            val installer = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }

            assertTrue(installer.install(pkg).isFailure)
            assertFalse(manager.modelDir(pkg.modelId, pkg.version).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archive(block: ArchiveBuilder.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                ArchiveBuilder(tar).block()
                tar.finish()
            }
        }
        return bytes.toByteArray()
    }

    private class ArchiveBuilder(private val tar: TarArchiveOutputStream) {
        fun runtimeFiles() {
            file("vits-piper-test/model.int8.onnx", byteArrayOf(1))
            file("vits-piper-test/tokens.txt", byteArrayOf(2))
            file("vits-piper-test/espeak-ng-data/readme", byteArrayOf(3))
        }

        fun file(name: String, content: ByteArray) {
            val entry = TarArchiveEntry(name)
            entry.size = content.size.toLong()
            tar.putArchiveEntry(entry)
            tar.write(content)
            tar.closeArchiveEntry()
        }

        fun link(name: String, target: String, type: Byte) {
            val entry = TarArchiveEntry(name, type)
            entry.linkName = target
            entry.size = 0
            tar.putArchiveEntry(entry)
            tar.closeArchiveEntry()
        }
    }
}
