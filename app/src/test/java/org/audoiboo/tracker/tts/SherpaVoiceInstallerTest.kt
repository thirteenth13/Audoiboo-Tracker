package org.audoiboo.tracker.tts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaVoiceInstallerTest {
    @Test
    fun installsVerifiedArchiveAndReturnsModelSpec() {
        val root = Files.createTempDirectory("voice-installer").toFile()
        try {
            val model = byteArrayOf(1, 2, 3, 4, 5)
            val archive = archiveOf(
                "vits-piper-test/model.int8.onnx" to model,
                "vits-piper-test/tokens.txt" to "a\nb\n".toByteArray(),
                "vits-piper-test/espeak-ng-data/readme" to "data".toByteArray(),
            )
            val pkg = testPackage(archive)
            val manager = VoiceModelManager(root)
            val installer = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }

            val spec = installer.install(pkg).getOrThrow()

            assertTrue(manager.isInstalled(spec))
            assertArrayEquals(model, manager.modelFile(spec).readBytes())
            assertTrue(File(manager.modelDir(spec), "tokens.txt").isFile)
            assertTrue(File(manager.modelDir(spec), "espeak-ng-data/readme").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsArchiveChecksumMismatchWithoutPublishingModel() {
        val root = Files.createTempDirectory("voice-installer-bad-sha").toFile()
        try {
            val archive = archiveOf(
                "vits-piper-test/model.int8.onnx" to byteArrayOf(1),
                "vits-piper-test/tokens.txt" to byteArrayOf(2),
            )
            val pkg = testPackage(archive).copy(archiveSha256 = "0".repeat(64))
            val manager = VoiceModelManager(root)
            val installer = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }

            assertTrue(installer.install(pkg).isFailure)
            assertFalse(manager.modelDir(pkg.modelId, pkg.version).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsPathTraversal() {
        val root = Files.createTempDirectory("voice-installer-traversal").toFile()
        try {
            val archive = archiveOf(
                "../outside" to "bad".toByteArray(),
                "vits-piper-test/model.int8.onnx" to byteArrayOf(1),
                "vits-piper-test/tokens.txt" to byteArrayOf(2),
            )
            val pkg = testPackage(archive)
            val manager = VoiceModelManager(root)
            val installer = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }

            assertTrue(installer.install(pkg).isFailure)
            assertFalse(File(root.parentFile, "outside").exists())
            assertFalse(manager.modelDir(pkg.modelId, pkg.version).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testPackage(archive: ByteArray): SherpaVoicePackage {
        val archiveFile = Files.createTempFile("voice", ".tar.bz2").toFile()
        return try {
            archiveFile.writeBytes(archive)
            SherpaVoicePackage(
                modelId = "test-model",
                version = "test-v1",
                language = "uk",
                displayName = "Test voice",
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/test.tar.bz2",
                archiveSha256 = VoiceModelManager.digest(archiveFile),
                archiveSizeBytes = archive.size.toLong(),
                modelFileName = "model.int8.onnx",
            )
        } finally {
            archiveFile.delete()
        }
    }

    private fun archiveOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
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
