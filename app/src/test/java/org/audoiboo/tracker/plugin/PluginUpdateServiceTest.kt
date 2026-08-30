package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory

class PluginUpdateServiceTest {
    @Test
    fun catalogCheckReturnsInstallablePluginAndDescription() {
        val service = PluginUpdateService(
            catalogFetcher = PluginCatalogFetcher { _, _ ->
                """
                {
                  "formatVersion": 1,
                  "plugins": [
                    {
                      "id": "baza-knig",
                      "name": "Baza-Knig",
                      "version": 1,
                      "apiVersion": $SOURCE_PLUGIN_API_VERSION,
                      "url": "https://example.org/baza-knig-1.abplugin",
                      "sha256": "${"a".repeat(64)}",
                      "description": "Baza-Knig source"
                    }
                  ]
                }
                """.trimIndent()
            },
            downloader = PluginUpdateDownloader { _, _, _ -> error("not used") }
        )

        val result = service.check(emptyList())

        assertTrue(result is PluginUpdateCheckResult.Success)
        result as PluginUpdateCheckResult.Success
        assertTrue(result.updates.isEmpty())
        assertEquals(1, result.installable.size)
        assertEquals("baza-knig", result.installable.single().id)
        assertEquals("Baza-Knig source", result.installable.single().description)
    }

    @Test
    fun verifiedPackageIsReturnedFromCache() = withTempDir { root ->
        val bytes = "plugin-package".toByteArray()
        val update = update(sha256(bytes))
        val service = PluginUpdateService(
            catalogFetcher = PluginCatalogFetcher { _, _ -> error("not used") },
            downloader = PluginUpdateDownloader { _, target, _ ->
                target.writeBytes(bytes)
                bytes.size.toLong()
            }
        )

        val result = service.downloadVerified(update, root)

        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertTrue(file.isFile)
        assertEquals(bytes.toList(), file.readBytes().toList())
    }

    @Test
    fun catalogEntryCanBeDownloadedBeforeFirstInstall() = withTempDir { root ->
        val bytes = "first-install-package".toByteArray()
        val entry = update(sha256(bytes)).entry.copy(id = "new-source")
        val service = PluginUpdateService(
            catalogFetcher = PluginCatalogFetcher { _, _ -> error("not used") },
            downloader = PluginUpdateDownloader { _, target, _ ->
                target.writeBytes(bytes)
                bytes.size.toLong()
            }
        )

        val result = service.downloadVerified(entry, root)

        assertTrue(result.isSuccess)
        assertEquals(bytes.toList(), result.getOrThrow().readBytes().toList())
    }

    @Test
    fun checksumMismatchRejectsDownloadedPackage() = withTempDir { root ->
        val update = update("0".repeat(64))
        val service = PluginUpdateService(
            catalogFetcher = PluginCatalogFetcher { _, _ -> error("not used") },
            downloader = PluginUpdateDownloader { _, target, _ ->
                target.writeText("tampered")
                target.length()
            }
        )

        val result = service.downloadVerified(update, root)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("checksum mismatch") == true)
    }

    @Test
    fun invalidCatalogEntryNeverCallsDownloader() = withTempDir { root ->
        var called = false
        val invalid = update("a".repeat(64)).copy(
            entry = update("a".repeat(64)).entry.copy(packageUrl = "http://example.org/source-2.abplugin")
        )
        val service = PluginUpdateService(
            catalogFetcher = PluginCatalogFetcher { _, _ -> error("not used") },
            downloader = PluginUpdateDownloader { _, _, _ ->
                called = true
                0L
            }
        )

        val result = service.downloadVerified(invalid, root)

        assertTrue(result.isFailure)
        assertFalse(called)
    }

    private fun update(hash: String) = PluginUpdate(
        entry = PluginCatalogEntry(
            id = "source",
            name = "Source",
            version = 2,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            packageUrl = "https://example.org/source-2.abplugin",
            sha256 = hash
        ),
        installedVersion = 1
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-plugin-update-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
