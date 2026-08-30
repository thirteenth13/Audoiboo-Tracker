package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourcePluginManagerPackageTest {
    @Test
    fun enabledPackageAppearsInActiveRegistryAndDisableRemovesIt() {
        val manager = SourcePluginManager(emptyList())
        val manifest = manifest("external-source")
        val registration = manager.registerPackageManifest(manifest, "/tmp/external-source")
        val plugin = fakePlugin(manifest)

        assertEquals(PluginState.DISABLED, registration.state)
        assertNull(manager.activeRegistry().byId("external-source"))

        val enabled = manager.enablePackagePlugin("external-source", plugin)
        assertNotNull(enabled)
        assertEquals(PluginState.ENABLED, manager.packageRegistration("external-source")?.state)
        assertNotNull(manager.activeRegistry().byId("external-source"))

        manager.disablePackagePlugin("external-source")
        assertEquals(PluginState.DISABLED, manager.packageRegistration("external-source")?.state)
        assertNull(manager.activeRegistry().byId("external-source"))
    }

    @Test
    fun packageCannotReplaceBuiltInSource() {
        val builtInManifest = manifest("same-id")
        val builtIn = fakePlugin(builtInManifest)
        val manager = SourcePluginManager(listOf(builtIn))

        val registration = manager.registerPackageManifest(builtInManifest.copy(version = 2), "/tmp/conflict")

        assertEquals(PluginState.QUARANTINED, registration.state)
        assertNull(manager.enablePackagePlugin("same-id", fakePlugin(builtInManifest.copy(version = 2))))
        assertEquals(1, manager.activeRegistry().plugins.size)
        assertEquals(1, manager.activeRegistry().byId("same-id")?.descriptor?.version)
    }

    private fun fakePlugin(manifest: PluginPackageManifest) = object : SourcePlugin {
        override val descriptor = SourceDescriptor(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            apiVersion = manifest.apiVersion,
            hosts = manifest.hosts,
            capabilities = manifest.capabilities
        )

        override fun supports(url: String): Boolean = url.contains("example.org")
    }

    private fun manifest(id: String) = PluginPackageManifest(
        id = id,
        name = id,
        version = 1,
        apiVersion = SOURCE_PLUGIN_API_VERSION,
        runtime = PluginRuntime.DECLARATIVE,
        hosts = setOf("example.org"),
        capabilities = setOf(SourceCapability.SERIES_LOOKUP),
        permissions = PluginPermissions(networkHosts = setOf("example.org")),
        entrypoints = mapOf("seriesLookup" to "series.rule")
    )
}
