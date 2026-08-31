package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DeclarativeLinkFallbackTest {
    @Test
    fun resolvesDownloadLinksFromHrefOrSrc() {
        val root = createTempDirectory("audoiboo-link-fallback-").toFile()
        try {
            File(root, "download.rule").writeText("downloads")
            val manifest = PluginPackageManifest(
                id = "fallback-test",
                name = "Fallback test",
                version = 1,
                apiVersion = SOURCE_PLUGIN_API_VERSION,
                runtime = PluginRuntime.DECLARATIVE,
                hosts = setOf("example.org"),
                capabilities = setOf(SourceCapability.DOWNLOAD_RESOLUTION),
                permissions = PluginPermissions(networkHosts = setOf("example.org")),
                entrypoints = mapOf("downloadResolution" to "download.rule")
            )
            val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
                PluginHttpResponse(
                    200,
                    request.url,
                    """
                        <a class='media' href='/audio/01.mp3'>one</a>
                        <audio class='media' src='/audio/02.mp3'></audio>
                        <source class='media' src='/audio/03.mp3'>
                    """.trimIndent()
                )
            })
            val runtime = DeclarativePluginRuntime(sandbox, DeclarativeEntrypointDecoder {
                DeclarativeEntrypoint.DownloadResolution(
                    items = RepeatedFields(item = ".media", link = "@href || @src"),
                    type = DownloadType.DIRECT_FILE
                )
            })

            val urls = runtime.resolveDownloads(manifest, root, "https://example.org/book").map { it.url }

            assertEquals(
                listOf(
                    "https://example.org/audio/01.mp3",
                    "https://example.org/audio/02.mp3",
                    "https://example.org/audio/03.mp3"
                ),
                urls
            )
        } finally {
            root.deleteRecursively()
        }
    }
}
