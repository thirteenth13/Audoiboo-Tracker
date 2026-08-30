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
    fun followsCanonicalSeriesLinkFromBookPageAndCleansTitle() = withTempDir { root ->
        File(root, "series.rule").writeText("series")
        val manifest = manifest(entrypoints = mapOf("seriesLookup" to "series.rule"))
        val decoder = DeclarativeEntrypointDecoder {
            DeclarativeEntrypoint.SeriesLookup(
                title = "h1",
                followLink = "a.series@href",
                titleRegex = "(?i)series\\s+[\"«]?(.+?)[\"»]?\\s+listen",
                books = RepeatedFields(item = "h2 a.book", title = "@text", link = "@href")
            )
        }
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            val body = when (request.url) {
                "https://example.org/book/10" -> "<h1>Book Ten</h1><a class='series' href='/series/star-blood'>Star Blood</a>"
                "https://example.org/series/star-blood" -> "<h1>Series \"Star Blood\" listen online</h1><h2><a class='book' href='/book/10'>Book Ten</a></h2>"
                else -> error("unexpected ${request.url}")
            }
            PluginHttpResponse(200, request.url, body)
        })
        val runtime = DeclarativePluginRuntime(sandbox, decoder)

        val series = runtime.resolveSeries(manifest, root, "https://example.org/book/10")!!

        assertEquals("Star Blood", series.title)
        assertEquals("https://example.org/series/star-blood", series.url)
        assertEquals(listOf("https://example.org/book/10"), series.books.map { it.url })
    }

    @Test
    fun resolvesBookMetadataFromSelectorsInsideSandbox() = withTempDir { root ->
        File(root, "book.rule").writeText("book")
        val manifest = manifest(
            capabilities = setOf(SourceCapability.BOOK_LOOKUP),
            entrypoints = mapOf("bookLookup" to "book.rule")
        )
        val runtime = runtime(
            body = """
                <html><body>
                  <h1>Book One - Author A author Author A</h1>
                  <a class='author'>Author A</a>
                  <a class='series'>Star Blood</a>
                  <span class='number'>3</span>
                  <img class='cover' src='/covers/one.jpg'>
                  <div class='desc'>Book description</div>
                </body></html>
            """.trimIndent(),
            decoder = DeclarativeEntrypointDecoder {
                DeclarativeEntrypoint.BookLookup(
                    title = "h1",
                    author = ".author",
                    seriesTitle = ".series",
                    seriesNumber = ".number",
                    coverUrl = ".cover@src",
                    description = ".desc",
                    titleRegex = "^(.+?)(?=\\s+-\\s+.+?\\s+author\\b)"
                )
            }
        )

        val book = runtime.resolveBook(manifest, root, "https://example.org/book/1")!!

        assertEquals("Book One", book.title)
        assertEquals(listOf("Author A"), book.authors.map { it.name })
        assertEquals("Star Blood", book.seriesTitle)
        assertEquals(3.0, book.seriesNumber)
        assertEquals("https://example.org/covers/one.jpg", book.coverUrl)
        assertEquals("Book description", book.description)
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
