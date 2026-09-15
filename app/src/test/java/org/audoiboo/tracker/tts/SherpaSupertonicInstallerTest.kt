package org.audoiboo.tracker.tts

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaSupertonicInstallerTest {
    @Test
    fun `installs complete seven-file Supertonic runtime and reuses it across languages`() {
        val root = Files.createTempDirectory("supertonic-installer").toFile()
        try {
            val archive = supertonicArchive()
            val ukPackage = testPackage(archive, "uk")
            val manager = VoiceModelManager(root)
            val installer = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }

            val ukSpec = installer.install(ukPackage).getOrThrow()
            val modelDir = manager.modelDir(ukSpec)
            SherpaVoiceInstaller.SUPERTONIC_RUNTIME_FILES.forEach { name ->
                assertTrue("missing $name", java.io.File(modelDir, name).isFile)
            }

            val ruPackage = ukPackage.copy(language = "ru")
            val ruSpec = SherpaVoiceInstaller(manager) { error("must reuse installed package") }
                .ensureInstalled(ruPackage).getOrThrow()
            assertEquals("ru", ruSpec.language)
            assertEquals(ukSpec.modelId, ruSpec.modelId)
            assertEquals(ukSpec.version, ruSpec.version)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects tampered Supertonic sibling runtime file`() {
        val root = Files.createTempDirectory("supertonic-tampered").toFile()
        try {
            val archive = supertonicArchive()
            val pkg = testPackage(archive, "uk")
            val manager = VoiceModelManager(root)
            val installer = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }
            val spec = installer.install(pkg).getOrThrow()

            java.io.File(manager.modelDir(spec), "voice.bin").appendText("tampered")

            assertNull(installer.installedSpec(pkg))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ensureInstalled repairs tampered Supertonic runtime from verified archive`() {
        val root = Files.createTempDirectory("supertonic-repair").toFile()
        try {
            val archive = supertonicArchive()
            val pkg = testPackage(archive, "uk")
            val manager = VoiceModelManager(root)
            var downloads = 0
            val installer = SherpaVoiceInstaller(manager) {
                downloads++
                ByteArrayInputStream(archive)
            }
            val initial = installer.ensureInstalled(pkg).getOrThrow()
            val voiceFile = java.io.File(manager.modelDir(initial), "voice.bin")
            val original = voiceFile.readBytes()
            voiceFile.appendText("tampered")

            assertNull(installer.installedSpec(pkg))
            val repaired = installer.ensureInstalled(pkg).getOrThrow()

            assertEquals(2, downloads)
            assertEquals(original.toList(), java.io.File(manager.modelDir(repaired), "voice.bin").readBytes().toList())
            assertNotNull(installer.installedSpec(pkg))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `rejects Supertonic archive missing any required runtime file`() {
        val root = Files.createTempDirectory("supertonic-missing").toFile()
        try {
            val archive = supertonicArchive(exclude = "voice.bin")
            val pkg = testPackage(archive, "uk")
            val manager = VoiceModelManager(root)
            val installer = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }

            assertTrue(installer.install(pkg).isFailure)
            assertFalse(manager.modelDir(pkg.modelId, pkg.version).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `Supertonic top-level release directory is stripped safely`() {
        val root = Files.createTempDirectory("supertonic-root").toFile()
        try {
            val archive = supertonicArchive()
            val pkg = testPackage(archive, "uk")
            val manager = VoiceModelManager(root)
            val spec = SherpaVoiceInstaller(manager) { ByteArrayInputStream(archive) }
                .install(pkg).getOrThrow()

            assertNotNull(java.io.File(manager.modelDir(spec), "tts.json").takeIf { it.isFile })
            assertFalse(java.io.File(manager.modelDir(spec), ARCHIVE_ROOT).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun supertonicArchive(exclude: String? = null): ByteArray {
        val files = SherpaVoiceInstaller.SUPERTONIC_RUNTIME_FILES
            .filterNot { it == exclude }
            .associateWith { name -> "test-$name".toByteArray() }
        return archiveOf(files)
    }

    private fun testPackage(archive: ByteArray, language: String): SherpaVoicePackage {
        val archiveFile = Files.createTempFile("supertonic", ".tar.bz2").toFile()
        return try {
            archiveFile.writeBytes(archive)
            SherpaVoicePackage(
                modelId = "sherpa-onnx-supertonic-3-test",
                version = "test-v1",
                language = language,
                displayName = "Supertonic test",
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-test.tar.bz2",
                archiveSha256 = VoiceModelManager.digest(archiveFile),
                archiveSizeBytes = archive.size.toLong(),
                modelFileName = "duration_predictor.int8.onnx",
                engineFamily = TtsEngineFamily.SUPERTONIC,
            )
        } finally {
            archiveFile.delete()
        }
    }

    private fun archiveOf(files: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        BZip2CompressorOutputStream(bytes).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                files.forEach { (name, content) ->
                    val entry = TarArchiveEntry("$ARCHIVE_ROOT/$name")
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

    companion object {
        private const val ARCHIVE_ROOT = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
    }
}
