package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUpdateCheckTest {
    @Test
    fun checkUsesInjectedDecoderAndClassifiesEntries() {
        var fetchedUrl: String? = null
        var decodedJson: String? = null
        val entries = listOf(
            entry(id = "installed", version = 3),
            entry(id = "new-source", version = 1)
        )
        val service = PluginUpdateService(
            catalogFetcher = PluginCatalogFetcher { url, _ ->
                fetchedUrl = url
                "catalog-payload"
            },
            downloader = PluginUpdateDownloader { _, _, _ -> error("not used") },
            catalogDecoder = PluginCatalogDecoder { json ->
                decodedJson = json
                entries
            }
        )

        val result = service.check(
            registrations = listOf(packageRegistration("installed", version = 2)),
            catalogUrl = "https://example.org/catalog.json"
        )

        assertTrue(result is PluginUpdateCheckResult.Success)
        result as PluginUpdateCheckResult.Success
        assertEquals("https://example.org/catalog.json", fetchedUrl)
        assertEquals("catalog-payload", decodedJson)
        assertEquals(listOf("installed"), result.updates.map { it.entry.id })
        assertEquals(listOf("new-source"), result.installable.map { it.id })
        assertEquals(entries, result.entries)
    }

    @Test
    fun decoderFailureBecomesFailedCheck() {
        val service = PluginUpdateService(
            catalogFetcher = PluginCatalogFetcher { _, _ -> "broken" },
            downloader = PluginUpdateDownloader { _, _, _ -> error("not used") },
            catalogDecoder = PluginCatalogDecoder { error("bad catalog") }
        )

        val result = service.check(emptyList())

        assertTrue(result is PluginUpdateCheckResult.Failed)
        assertEquals("bad catalog", (result as PluginUpdateCheckResult.Failed).reason)
    }

    private fun entry(id: String, version: Int) = PluginCatalogEntry(
        id = id,
        name = id,
        version = version,
        apiVersion = SOURCE_PLUGIN_API_VERSION,
        packageUrl = "https://example.org/$id-$version.abplugin",
        sha256 = "a".repeat(64)
    )

    private fun packageRegistration(id: String, version: Int) = SourcePluginRegistration(
        descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = version,
            hosts = setOf("example.org"),
            capabilities = setOf(SourceCapability.SERIES_LOOKUP)
        ),
        packageId = id,
        displayName = id,
        origin = PluginOrigin.PACKAGE,
        state = PluginState.ENABLED
    )
}
