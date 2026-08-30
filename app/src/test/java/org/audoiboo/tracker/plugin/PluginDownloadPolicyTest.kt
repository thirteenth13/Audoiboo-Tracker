package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginDownloadPolicyTest {
    private val manifest = PluginPackageManifest(
        id = "download-test",
        name = "Download test",
        version = 1,
        apiVersion = SOURCE_PLUGIN_API_VERSION,
        hosts = setOf("source.example"),
        capabilities = setOf(SourceCapability.DOWNLOAD_RESOLUTION),
        permissions = PluginPermissions(
            networkHosts = setOf("source.example"),
            downloadHosts = setOf("cdn.example")
        )
    )

    @Test
    fun allowsDeclaredDownloadHost() {
        assertTrue(
            PluginDownloadPolicy.isAllowed(
                manifest,
                DownloadCandidate(DownloadType.ARCHIVE, "https://cdn.example/files/book.zip")
            )
        )
    }

    @Test
    fun rejectsUndeclaredDownloadHost() {
        assertFalse(
            PluginDownloadPolicy.isAllowed(
                manifest,
                DownloadCandidate(DownloadType.ARCHIVE, "https://evil.example/files/book.zip")
            )
        )
    }

    @Test
    fun rejectsCredentialsAndNonHttpSchemes() {
        assertFalse(
            PluginDownloadPolicy.isAllowed(
                manifest,
                DownloadCandidate(DownloadType.ARCHIVE, "https://user:pass@cdn.example/book.zip")
            )
        )
        assertFalse(
            PluginDownloadPolicy.isAllowed(
                manifest,
                DownloadCandidate(DownloadType.ARCHIVE, "file:///sdcard/book.zip")
            )
        )
    }

    @Test
    fun magnetCandidatesRequireMagnetScheme() {
        assertTrue(
            PluginDownloadPolicy.isAllowed(
                manifest,
                DownloadCandidate(DownloadType.MAGNET, "magnet:?xt=urn:btih:0123456789abcdef")
            )
        )
        assertFalse(
            PluginDownloadPolicy.isAllowed(
                manifest,
                DownloadCandidate(DownloadType.MAGNET, "https://cdn.example/book.torrent")
            )
        )
    }

    @Test
    fun oldManifestFallsBackToNetworkHostsForDownloads() {
        val legacy = manifest.copy(
            permissions = PluginPermissions(networkHosts = setOf("source.example"))
        )
        assertTrue(
            PluginDownloadPolicy.isAllowed(
                legacy,
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://source.example/book.mp3")
            )
        )
        assertFalse(
            PluginDownloadPolicy.isAllowed(
                legacy,
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://cdn.example/book.mp3")
            )
        )
    }
}
