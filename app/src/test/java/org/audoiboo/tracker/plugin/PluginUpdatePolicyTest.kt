package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUpdatePolicyTest {
    @Test
    fun choosesNewestCompatibleUpdateForInstalledPackage() {
        val registrations = listOf(registration("source", 2))
        val entries = listOf(
            entry("source", 3),
            entry("source", 5),
            entry("other", 9)
        )

        val updates = PluginUpdatePolicy.availableUpdates(entries, registrations)

        assertEquals(1, updates.size)
        assertEquals(2, updates.single().installedVersion)
        assertEquals(5, updates.single().entry.version)
    }

    @Test
    fun ignoresBuiltInPackagesAndNonNewerVersions() {
        val builtIn = registration("builtin", 1, origin = PluginOrigin.BUILT_IN)
        val external = registration("source", 4)

        val updates = PluginUpdatePolicy.availableUpdates(
            listOf(entry("builtin", 8), entry("source", 4), entry("source", 3)),
            listOf(builtIn, external)
        )

        assertTrue(updates.isEmpty())
    }

    @Test
    fun rejectsHttpAndMalformedChecksumEntries() {
        val http = entry("source", 2).copy(packageUrl = "http://example.org/source.abplugin")
        val badHash = entry("source", 2).copy(sha256 = "abcd")

        assertTrue(PluginUpdatePolicy.validateEntry(http)?.contains("HTTPS") == true)
        assertTrue(PluginUpdatePolicy.validateEntry(badHash)?.contains("SHA-256") == true)
    }

    @Test
    fun rejectsWrongPluginApiAndWrongExtension() {
        val wrongApi = entry("source", 2).copy(apiVersion = SOURCE_PLUGIN_API_VERSION + 1)
        val wrongExtension = entry("source", 2).copy(packageUrl = "https://example.org/source.zip")

        assertTrue(PluginUpdatePolicy.validateEntry(wrongApi)?.contains("unsupported plugin API") == true)
        assertTrue(PluginUpdatePolicy.validateEntry(wrongExtension)?.contains(".abplugin") == true)
    }

    private fun entry(id: String, version: Int) = PluginCatalogEntry(
        id = id,
        name = id,
        version = version,
        apiVersion = SOURCE_PLUGIN_API_VERSION,
        packageUrl = "https://example.org/$id-$version.abplugin",
        sha256 = "a".repeat(64)
    )

    private fun registration(
        id: String,
        version: Int,
        origin: PluginOrigin = PluginOrigin.PACKAGE
    ) = SourcePluginRegistration(
        descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = version,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            hosts = setOf("example.org"),
            capabilities = emptySet()
        ),
        packageId = id,
        displayName = id,
        origin = origin,
        state = PluginState.DISABLED
    )
}
