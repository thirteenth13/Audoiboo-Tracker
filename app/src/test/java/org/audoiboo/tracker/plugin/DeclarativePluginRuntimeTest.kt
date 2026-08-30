package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DeclarativePluginRuntimeTest {
    @Test
    fun resolvesSeriesFromSelectorsInsideSandbox() = withTempDir { root ->
        File(root, "series.rule").writeText("series")
        val manifest = manifest(entrypoints = mapOf("seriesLookup" to "series.rule"))
        val runtime = runtime(
            body = """
                <html><body>
                  <h1>Star Blood</h1>
                  <div class='desc'>Cycle description</div>
                  <article class='book'><a href='/b1'><span class='title'>Book One</span></a><span class='author'>Author A</span><span class='num'>1</span></article>
                  <article class='book'><a href='/b2'><span class='title'>Book Two</span></a><span class='author'>Author A</span><span class='num'>2</span></article>
                </body></html>
            """.trimIndent(),
            decoder = DeclarativeEntrypointDecoder {
                DeclarativeEntrypoint.SeriesLookup(
                    title = "h1",
                    description = ".desc",
                    books = RepeatedFields(
                        item = ".book",
                        title = ".title",
                        link = "a@href",
                        author = ".author",
                        number = ".num"
                    )
                )
            }
        )

        val series = runtime.resolveSeries(manifest, root, "https://example.org/cycle")!!

        assertEquals("Star Blood", series.title)
        assertEquals("Cycle description", series.description)
        assertEquals(2, series.books.size)
        assertEquals("https://example.org/b1", series.books[0].url)
        assertEquals(1.0, series.books[0].number)
        assertEquals(listOf("Author A"), series.authors.map { it.name })
    }

    @Test
    fun resolvesArchiveCandidatesAndDeduplicatesUrls() = withTempDir { root ->
        File(root, "download.rule").writeText("downloads")
        val manifest = manifest(
            capabilities = setOf(SourceCapability.DOWNLOAD_RESOLUTION),
            entrypoints = mapOf("downloadResolution" to "download.rule")
        )
        val runtime = runtime(
            body = """
                <a class='download' href='/files/book.zip'>one</a>
                <a class='download' href='/files/book.zip'>duplicate</a>
                <a class='download' href='/files/book2.zip'>two</a>
            """.trimIndent(),
            decoder = DeclarativeEntrypointDecoder {
                DeclarativeEntrypoint.DownloadResolution(
                    items = RepeatedFields(item = ".download", link = "@href"),
                    type = DownloadType.ARCHIVE
                )
            }
        )

        val results = runtime.resolveDownloads(manifest, root, "https://example.org/book")

        assertEquals(2, results.size)
        assertTrue(results.all { it.type == DownloadType.ARCHIVE })
        assertEquals("https://example.org/files/book.zip", results[0].url)
    }

    @Test
    fun refusesEntrypointOutsidePackageDirectory() = withTempDir { root ->
        val manifest = manifest(entrypoints = mapOf("seriesLookup" to "../outside.rule"))
        val runtime = runtime("<h1>x</h1>", DeclarativeEntrypointDecoder {
            DeclarativeEntrypoint.SeriesLookup("h1")
        })

        assertThrows(PluginSandboxViolation::class.java) {
            runtime.resolveSeries(manifest, root, "https://example.org/cycle")
        }
    }

    @Test
    fun capabilityMustBeDeclared() = withTempDir { root ->
        File(root, "series.rule").writeText("series")
        val manifest = manifest(
            capabilities = setOf(SourceCapability.DOWNLOAD_RESOLUTION),
            entrypoints = mapOf("seriesLookup" to "series.rule")
        )
        val runtime = runtime("<h1>x</h1>", DeclarativeEntrypointDecoder {
            DeclarativeEntrypoint.SeriesLookup("h1")
        })

        assertThrows(PluginSandboxViolation::class.java) {
            runtime.resolveSeries(manifest, root, "https://example.org/cycle")
        }
    }

    private fun runtime(body: String, decoder: DeclarativeEntrypointDecoder): DeclarativePluginRuntime {
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            PluginHttpResponse(200, request.url, body)
        })
        return DeclarativePluginRuntime(sandbox, decoder)
    }

    private fun manifest(
        capabilities: Set<SourceCapability> = setOf(SourceCapability.SERIES_LOOKUP),
        entrypoints: Map<String, String>
    ) = PluginPackageManifest(
        id = "declarative-test",
        name = "Declarative test",
        version = 1,
        apiVersion = SOURCE_PLUGIN_API_VERSION,
        runtime = PluginRuntime.DECLARATIVE,
        hosts = setOf("example.org"),
        capabilities = capabilities,
        permissions = PluginPermissions(networkHosts = setOf("example.org")),
        entrypoints = entrypoints
    )

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-declarative-runtime-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
