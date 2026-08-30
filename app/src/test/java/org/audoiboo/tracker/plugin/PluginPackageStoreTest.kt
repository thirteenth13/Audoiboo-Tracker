package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PluginPackageStoreTest {
    @Test
    fun scanRestoresInstalledPackageRegistration() = withTempDir { root ->
        writeVersion(root, "baza-knig", 2, validManifest("baza-knig", 2))
        File(root, "installed/baza-knig/active-version").writeText("2")
        val manager = SourcePluginManager(emptyList())
        val store = testStore(root, manager)

        val result = store.scanInstalled()

        assertEquals(1, result.registrations.size)
        assertEquals("baza-knig", result.registrations.single().packageId)
        assertEquals(PluginState.DISABLED, result.registrations.single().state)
        assertEquals(2, store.readActiveVersion("baza-knig"))
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun corruptActiveVersionIsQuarantinedAndPreviousVersionRecovered() = withTempDir { root ->
        writeVersion(root, "baza-knig", 1, validManifest("baza-knig", 1))
        writeVersion(root, "baza-knig", 2, "not json")
        File(root, "installed/baza-knig/active-version").writeText("2")
        val store = testStore(root, SourcePluginManager(emptyList()), clockMillis = { 1234L })

        val result = store.scanInstalled()

        assertEquals(1, store.readActiveVersion("baza-knig"))
        assertTrue("baza-knig" in result.recoveredPlugins)
        assertTrue("baza-knig@2" in result.quarantinedPlugins)
        assertTrue(File(root, "quarantine/baza-knig/2-1234").isDirectory)
        assertFalse(File(root, "installed/baza-knig/versions/2").exists())
    }

    @Test
    fun manifestIdentityMismatchIsQuarantined() = withTempDir { root ->
        writeVersion(root, "expected", 1, validManifest("other", 1))
        File(root, "installed/expected/active-version").writeText("1")
        val store = testStore(root, SourcePluginManager(emptyList()), clockMillis = { 9L })

        val result = store.scanInstalled()

        assertTrue("expected@1" in result.quarantinedPlugins)
        assertEquals(null, store.readActiveVersion("expected"))
        assertTrue(File(root, "quarantine/expected/1-9").isDirectory)
    }

    @Test
    fun explicitQuarantineFallsBackToPreviousVersion() = withTempDir { root ->
        writeVersion(root, "source", 1, validManifest("source", 1))
        writeVersion(root, "source", 2, validManifest("source", 2))
        File(root, "installed/source/active-version").writeText("2")
        val manager = SourcePluginManager(emptyList())
        val store = testStore(root, manager, clockMillis = { 77L })
        store.scanInstalled()

        assertTrue(store.quarantineActive("source", "runtime failures"))
        assertEquals(1, store.readActiveVersion("source"))
        assertTrue(File(root, "quarantine/source/2-77/quarantine-reason.txt").isFile)
        assertEquals(PluginState.QUARANTINED, manager.packageRegistration("source")?.state)
    }

    @Test
    fun quarantinedVersionCanBeRestored() = withTempDir { root ->
        writeVersion(root, "source", 1, validManifest("source", 1))
        File(root, "installed/source/active-version").writeText("1")
        val manager = SourcePluginManager(emptyList())
        val store = testStore(root, manager, clockMillis = { 100L })
        store.scanInstalled()
        assertTrue(store.quarantineActive("source", "manual test"))

        assertTrue(store.restoreLatestQuarantined("source"))
        assertEquals(1, store.readActiveVersion("source"))
        assertTrue(File(root, "installed/source/versions/1/plugin.json").isFile)
    }

    @Test
    fun disabledMarkerPersistsAcrossManagerRecreation() = withTempDir { root ->
        writeVersion(root, "source", 1, validManifest("source", 1))
        File(root, "installed/source/active-version").writeText("1")
        val firstStore = testStore(root, SourcePluginManager(emptyList()))
        firstStore.scanInstalled()
        assertTrue(firstStore.disable("source"))

        val secondStore = testStore(root, SourcePluginManager(emptyList()))
        secondStore.scanInstalled()
        assertTrue(secondStore.isDisabled("source"))
        assertTrue(secondStore.clearDisabled("source"))
        assertFalse(secondStore.isDisabled("source"))
    }

    private fun testStore(
        root: File,
        manager: SourcePluginManager,
        clockMillis: () -> Long = { System.currentTimeMillis() }
    ) = PluginPackageStore(
        pluginRoot = root,
        manager = manager,
        manifestDecoder = PluginManifestDecoder(::decodeTestManifest),
        clockMillis = clockMillis
    )

    private fun decodeTestManifest(json: String): PluginPackageManifest {
        if (!json.trimStart().startsWith("{")) error("invalid json")
        fun stringField(name: String): String = Regex("\\\"$name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(json)?.groupValues?.get(1) ?: error("missing $name")
        fun intField(name: String): Int = Regex("\\\"$name\\\"\\s*:\\s*(\\d+)")
            .find(json)?.groupValues?.get(1)?.toInt() ?: error("missing $name")

        return PluginPackageManifest(
            id = stringField("id"),
            name = stringField("name"),
            version = intField("version"),
            apiVersion = intField("apiVersion"),
            runtime = PluginRuntime.DECLARATIVE,
            hosts = setOf("example.org"),
            capabilities = setOf(SourceCapability.SERIES_LOOKUP),
            permissions = PluginPermissions(networkHosts = setOf("example.org")),
            entrypoints = emptyMap()
        )
    }

    private fun writeVersion(root: File, id: String, version: Int, manifest: String) {
        val dir = File(root, "installed/$id/versions/$version").apply { mkdirs() }
        File(dir, PLUGIN_MANIFEST_FILE).writeText(manifest)
    }

    private fun validManifest(id: String, version: Int): String = """
        {
          "id": "$id",
          "name": "$id",
          "version": $version,
          "apiVersion": $SOURCE_PLUGIN_API_VERSION,
          "runtime": "DECLARATIVE",
          "hosts": ["example.org"],
          "capabilities": ["SERIES_LOOKUP"],
          "permissions": {"networkHosts": ["example.org"]},
          "entrypoints": {}
        }
    """.trimIndent()

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-plugin-store-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
