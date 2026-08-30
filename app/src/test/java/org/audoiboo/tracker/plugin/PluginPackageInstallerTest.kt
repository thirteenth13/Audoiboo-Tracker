package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PluginPackageInstallerTest {
    @Test
    fun validPackageIsStagedAndActivatedButNotExecuted() {
        withTempDir { root ->
            val packageFile = File(root, "baza.abplugin")
            writeZip(packageFile, mapOf(
                "plugin.json" to "{}".toByteArray(),
                "scripts/series.js" to "return [];".toByteArray()
            ))
            val manager = SourcePluginManager(listOf(AudiobooSourcePlugin))
            val installer = PluginPackageInstaller(
                pluginRoot = File(root, "plugins"),
                manager = manager,
                manifestDecoder = PluginManifestDecoder { manifest("baza-knig", version = 1) }
            )

            val result = installer.install(packageFile)
            assertTrue(result is PluginInstallResult.Installed)
            val installed = result as PluginInstallResult.Installed
            assertTrue(File(installed.installDir, "plugin.json").isFile)
            assertTrue(File(installed.installDir, "scripts/series.js").isFile)
            assertEquals(1, installer.activeVersion("baza-knig"))
            assertEquals(PluginState.DISABLED, installed.registration.state)
            assertEquals(null, manager.activeRegistry().byId("baza-knig"))
        }
    }

    @Test
    fun zipSlipEntryIsRejectedBeforeExtraction() {
        withTempDir { root ->
            val packageFile = File(root, "evil.abplugin")
            writeZip(packageFile, mapOf(
                "plugin.json" to "{}".toByteArray(),
                "../outside.txt" to "owned".toByteArray()
            ))
            val installer = PluginPackageInstaller(
                File(root, "plugins"),
                SourcePluginManager(emptyList()),
                PluginManifestDecoder { manifest("evil-source", 1) }
            )

            val result = installer.install(packageFile)
            assertTrue(result is PluginInstallResult.Rejected)
            assertFalse(File(root, "outside.txt").exists())
        }
    }

    @Test
    fun oversizedExpandedEntryIsRejected() {
        withTempDir { root ->
            val packageFile = File(root, "large.abplugin")
            writeZip(packageFile, mapOf(
                "plugin.json" to "{}".toByteArray(),
                "resources/data.txt" to ByteArray(1024) { 1 }
            ))
            val installer = PluginPackageInstaller(
                File(root, "plugins"),
                SourcePluginManager(emptyList()),
                PluginManifestDecoder { manifest("large-source", 1) },
                PluginArchiveLimits(
                    maxEntries = 16,
                    maxCompressedBytes = 1024 * 1024,
                    maxUncompressedBytes = 512,
                    maxEntryBytes = 512,
                    maxManifestBytes = 128
                )
            )

            assertTrue(installer.install(packageFile) is PluginInstallResult.Rejected)
        }
    }

    @Test
    fun updateKeepsPreviousVersionAndCanRollbackPointer() {
        withTempDir { root ->
            val packageFile = File(root, "source.abplugin")
            writeZip(packageFile, mapOf("plugin.json" to "{}".toByteArray()))
            var version = 1
            val installer = PluginPackageInstaller(
                File(root, "plugins"),
                SourcePluginManager(emptyList()),
                PluginManifestDecoder { manifest("test-source", version) }
            )

            assertTrue(installer.install(packageFile) is PluginInstallResult.Installed)
            version = 2
            assertTrue(installer.install(packageFile) is PluginInstallResult.Installed)
            assertEquals(2, installer.activeVersion("test-source"))
            assertTrue(installer.rollback("test-source"))
            assertEquals(1, installer.activeVersion("test-source"))
        }
    }

    @Test
    fun downgradeIsRejected() {
        withTempDir { root ->
            val packageFile = File(root, "source.abplugin")
            writeZip(packageFile, mapOf("plugin.json" to "{}".toByteArray()))
            var version = 2
            val installer = PluginPackageInstaller(
                File(root, "plugins"),
                SourcePluginManager(emptyList()),
                PluginManifestDecoder { manifest("test-source", version) }
            )
            assertTrue(installer.install(packageFile) is PluginInstallResult.Installed)
            version = 1
            assertTrue(installer.install(packageFile) is PluginInstallResult.Rejected)
            assertEquals(2, installer.activeVersion("test-source"))
        }
    }

    private fun manifest(id: String, version: Int) = PluginPackageManifest(
        id = id,
        name = id,
        version = version,
        apiVersion = SOURCE_PLUGIN_API_VERSION,
        hosts = setOf("example.org"),
        capabilities = setOf(SourceCapability.SERIES_LOOKUP),
        permissions = PluginPermissions(networkHosts = setOf("example.org")),
        entrypoints = mapOf("series" to "scripts/series.js")
    )

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDir(prefix = "audoiboo-plugin-test-")
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
