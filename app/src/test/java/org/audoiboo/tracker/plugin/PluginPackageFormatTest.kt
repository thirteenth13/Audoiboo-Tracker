package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPackageFormatTest {
    @Test
    fun validManifestPassesPolicy() {
        val manifest = PluginPackageManifest(
            id = "baza-knig",
            name = "Baza-Knig",
            version = 1,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            hosts = setOf("baza-knig.top"),
            capabilities = setOf(SourceCapability.SERIES_SEARCH, SourceCapability.DOWNLOAD_RESOLUTION),
            permissions = PluginPermissions(networkHosts = setOf("baza-knig.top")),
            entrypoints = mapOf("series" to "scripts/series.js")
        )

        assertTrue(PluginPackagePolicy.validate(manifest).valid)
        assertEquals("baza-knig-1", PluginPackagePolicy.packageDirectoryName(manifest.id, manifest.version))
    }

    @Test
    fun traversalEntrypointIsRejected() {
        val manifest = PluginPackageManifest(
            id = "baza-knig",
            name = "Baza-Knig",
            version = 1,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            hosts = setOf("baza-knig.top"),
            capabilities = emptySet(),
            permissions = PluginPermissions(networkHosts = setOf("baza-knig.top")),
            entrypoints = mapOf("series" to "../series.js")
        )

        val validation = PluginPackagePolicy.validate(manifest)
        assertFalse(validation.valid)
        assertTrue(validation.errors.any { it.startsWith("Unsafe entrypoint path") })
    }

    @Test
    fun undeclaredNetworkHostIsRejected() {
        val manifest = PluginPackageManifest(
            id = "baza-knig",
            name = "Baza-Knig",
            version = 1,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            hosts = setOf("baza-knig.top"),
            capabilities = emptySet(),
            permissions = PluginPermissions(networkHosts = emptySet())
        )

        assertFalse(PluginPackagePolicy.validate(manifest).valid)
    }

    @Test
    fun malformedManifestCanBeQuarantinedWithoutConstructingDescriptor() {
        val manager = SourcePluginManager(listOf(AudiobooSourcePlugin))
        val registration = manager.registerPackageManifest(
            PluginPackageManifest(
                id = "bad-package",
                name = "Bad package",
                version = 1,
                apiVersion = SOURCE_PLUGIN_API_VERSION,
                hosts = emptySet(),
                capabilities = emptySet()
            ),
            packagePath = "quarantine/bad-package"
        )

        assertEquals(PluginState.QUARANTINED, registration.state)
        assertNull(registration.descriptor)
    }

    @Test
    fun packageExtensionIsCaseInsensitive() {
        assertTrue(PluginPackagePolicy.isPluginPackageName("baza-knig.abplugin"))
        assertTrue(PluginPackagePolicy.isPluginPackageName("BAZA.ABPLUGIN"))
        assertFalse(PluginPackagePolicy.isPluginPackageName("baza-knig.zip"))
    }

    @Test
    fun builtInPluginIsRegisteredAndPackageConflictIsQuarantined() {
        val manager = SourcePluginManager(listOf(AudiobooSourcePlugin))
        val conflict = manager.registerPackageManifest(
            PluginPackageManifest(
                id = "audioboo",
                name = "Fake Audioboo",
                version = 2,
                apiVersion = SOURCE_PLUGIN_API_VERSION,
                hosts = setOf("audioboo.org"),
                capabilities = setOf(SourceCapability.SERIES_LOOKUP),
                permissions = PluginPermissions(networkHosts = setOf("audioboo.org"))
            ),
            packagePath = "installed/audioboo"
        )

        assertEquals(PluginState.QUARANTINED, conflict.state)
        assertEquals(AudiobooSourcePlugin, manager.activeRegistry().byId("audioboo"))
    }

    @Test
    fun compatiblePackageWaitsDisabledForSandboxLoader() {
        val manager = SourcePluginManager(listOf(AudiobooSourcePlugin))
        val registration = manager.registerPackageManifest(
            PluginPackageManifest(
                id = "knigavuhe",
                name = "Knigavuhe",
                version = 1,
                apiVersion = SOURCE_PLUGIN_API_VERSION,
                hosts = setOf("knigavuhe.org"),
                capabilities = setOf(SourceCapability.SERIES_SEARCH),
                permissions = PluginPermissions(networkHosts = setOf("knigavuhe.org"))
            ),
            packagePath = "installed/knigavuhe"
        )

        assertEquals(PluginState.DISABLED, registration.state)
        assertEquals(null, manager.activeRegistry().byId("knigavuhe"))
    }
}
