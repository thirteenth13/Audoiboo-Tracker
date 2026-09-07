package org.audoiboo.tracker.plugin

import java.io.File
import java.nio.file.Files.createTempDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DeclarativePluginRuntimeTest {
    @Test
    fun resolvesSeriesFromSelectorsInsideSandbox() = withTempDir { root ->
        File(root, "series.rule").writeText("series")
        val manifest = manifest(entrypoints = mapOf("seriesLookup" to "series.rule"))
        val runtime = runtime("<h1>Star Blood</h1><div class='desc'>Description</div><a class='book' href='/book/1'>One</a><a class='book' href='/book/2'>Two</a>", DeclarativeEntrypointDecoder { DeclarativeEntrypoint.SeriesLookup(title = "h1", description = ".desc", books = RepeatedFields(item = ".book", title = "@text", link = "@href")) })
        val series = runtime.resolveSeries(manifest, root, "https://example.org/series/star-blood")!!
        assertEquals("Star Blood", series.title); assertEquals("Description", series.description); assertEquals(listOf("https://example.org/book/1", "https://example.org/book/2"), series.books.map { it.url })
    }

    @Test
    fun searchesSeriesFromSelectorsInsideSandbox() = withTempDir { root ->
        File(root, "search.rule").writeText("search")
        val manifest = manifest(capabilities = setOf(SourceCapability.SERIES_SEARCH), entrypoints = mapOf("seriesSearch" to "search.rule"))
        val runtime = runtime("<a class='result' href='/series/1'>Star Blood</a><a class='result' href='/series/2'>Stellar</a>", DeclarativeEntrypointDecoder { DeclarativeEntrypoint.SeriesSearch("https://example.org/search?q={query}", RepeatedFields(item = ".result", title = "@text", link = "@href")) })
        val results = runtime.searchSeries(manifest, root, SeriesSearchQuery("Star Blood"))
        assertEquals(2, results.size); assertEquals("https://example.org/series/1", results[0].series.url)
    }

    @Test
    fun resolvesSeriesSupplementPages() = withTempDir { root ->
        File(root, "series.rule").writeText("series")
        val manifest = manifest(entrypoints = mapOf("seriesLookup" to "series.rule"))
        val decoder = DeclarativeEntrypointDecoder { DeclarativeEntrypoint.SeriesLookup(title = "h1", supplement = SeriesSupplement(startLink = "a.author@href", items = RepeatedFields(item = ".book", title = ".title", link = "a@href"), seriesTitle = ".series", nextPage = "a.next@href", maxPages = 2)) }
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            val body = when (request.url) {
                "https://example.org/series/star-blood" -> "<h1>Star Blood</h1><a class='author' href='/author/1'>Author</a>"
                "https://example.org/author/1" -> "<div class='book'><span class='series'>Star Blood</span><span class='title'>Ten</span><a href='/book/10'>Ten</a></div><a class='next' href='/author/1?page=2'>Next</a>"
                "https://example.org/author/1?page=2" -> "<div class='book'><span class='series'>Star Blood</span><span class='title'>Eleven</span><a href='/book/11'>Eleven</a></div>"
                else -> error("unexpected ${request.url}")
            }
            PluginHttpResponse(200, request.url, body)
        })
        val series = DeclarativePluginRuntime(sandbox, decoder).resolveSeries(manifest, root, "https://example.org/series/star-blood")!!
        assertEquals(listOf("https://example.org/book/10", "https://example.org/book/11"), series.books.map { it.url })
    }

    @Test
    fun followsCanonicalSeriesLinkFromBookPageAndCleansTitle() = withTempDir { root ->
        File(root, "series.rule").writeText("series")
        val manifest = manifest(entrypoints = mapOf("seriesLookup" to "series.rule"))
        val decoder = DeclarativeEntrypointDecoder {
            DeclarativeEntrypoint.SeriesLookup(title = "h1", followLink = "a.series@href", titleRegex = "(?i)series\\s+[\"«]?(.+?)[\"»]?\\s+listen", books = RepeatedFields(item = "h2 a.book", title = "@text", link = "@href"))
        }
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            val body = when (request.url) {
                "https://example.org/book/10" -> "<h1>Book Ten</h1><a class='series' href='/series/star-blood'>Star Blood</a>"
                "https://example.org/series/star-blood" -> "<h1>Series \"Star Blood\" listen online</h1><h2><a class='book' href='/book/10'>Book Ten</a></h2>"
                else -> error("unexpected ${request.url}")
            }
            PluginHttpResponse(200, request.url, body)
        })
        val series = DeclarativePluginRuntime(sandbox, decoder).resolveSeries(manifest, root, "https://example.org/book/10")!!
        assertEquals("Star Blood", series.title)
        assertEquals("https://example.org/series/star-blood", series.url)
        assertEquals(listOf("https://example.org/book/10"), series.books.map { it.url })
    }

    @Test
    fun discoversIzibSeriesThroughLetterAuthorDirectory() {
        val manifest = PluginPackageManifest(id = "izib", name = "Izib", version = 3, apiVersion = SOURCE_PLUGIN_API_VERSION, hosts = setOf("pda.izib.uk", "izib.uk"), capabilities = setOf(SourceCapability.SERIES_DISCOVERY), permissions = PluginPermissions(networkHosts = setOf("pda.izib.uk", "izib.uk")))
        val requested = mutableListOf<String>()
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            requested += request.url
            val body = when (request.url) {
                "https://pda.izib.uk/authors?l=%D0%A0" -> "<a href='/author1'>Other Author</a>"
                "https://pda.izib.uk/authors?l=%D0%9F" -> "<a href='/author2176'>Роман Прокофьев</a>"
                "https://pda.izib.uk/author2176" -> "<a href='/serie8524'>Звездная Кровь</a><a href='/serie9999'>Стеллар</a>"
                else -> error("unexpected ${request.url}")
            }
            PluginHttpResponse(200, request.url, body)
        })
        val results = DeclarativePluginRuntime(sandbox).discoverIzibSeries(manifest, CanonicalSeriesMatchInput(id = "star-blood", title = "Звёздная кровь", authors = listOf("Роман Прокофьев")), maxAuthorPages = 2)
        assertEquals(1, results.size)
        assertEquals("https://pda.izib.uk/serie8524", results.single().series.url)
        assertEquals("Звездная Кровь", results.single().series.title)
        assertEquals(3, requested.size)
    }

    @Test
    fun izibDiscoveryStopsAtConfiguredAuthorPageBound() {
        val manifest = PluginPackageManifest(id = "izib", name = "Izib", version = 3, apiVersion = SOURCE_PLUGIN_API_VERSION, hosts = setOf("pda.izib.uk", "izib.uk"), capabilities = setOf(SourceCapability.SERIES_DISCOVERY), permissions = PluginPermissions(networkHosts = setOf("pda.izib.uk", "izib.uk")))
        var requests = 0
        val runtime = DeclarativePluginRuntime(PluginSandbox(PluginHttpTransport { request, _ -> requests++; PluginHttpResponse(200, request.url, "<a href='/author1'>Someone Else</a>") }))
        val results = runtime.discoverIzibSeries(manifest, CanonicalSeriesMatchInput("c", "Star Blood", authors = listOf("Missing Author")), maxAuthorPages = 3)
        assertTrue(results.isEmpty())
        // Letter-directory discovery is bounded by distinct author initials; maxAuthorPages is an upper cap.
        assertEquals(1, requests)
    }

    @Test
    fun resolvesBookMetadataFromSelectorsInsideSandbox() = withTempDir { root ->
        File(root, "book.rule").writeText("book")
        val manifest = manifest(capabilities = setOf(SourceCapability.BOOK_LOOKUP), entrypoints = mapOf("bookLookup" to "book.rule"))
        val runtime = runtime("<html><body><h1>Book One - Author A author Author A</h1><a class='author'>Author A</a><a class='series'>Star Blood</a><span class='number'>3</span><img class='cover' src='/covers/one.jpg'><div class='desc'>Book description</div></body></html>", DeclarativeEntrypointDecoder { DeclarativeEntrypoint.BookLookup(title = "h1", author = ".author", seriesTitle = ".series", seriesNumber = ".number", coverUrl = ".cover@src", description = ".desc", titleRegex = "^(.+?)(?=\\s+-\\s+.+?\\s+author\\b)") })
        val book = runtime.resolveBook(manifest, root, "https://example.org/book/1")!!
        assertEquals("Book One", book.title); assertEquals(listOf("Author A"), book.authors.map { it.name }); assertEquals("Star Blood", book.seriesTitle); assertEquals(3.0, book.seriesNumber); assertEquals("https://example.org/covers/one.jpg", book.coverUrl); assertEquals("Book description", book.description)
    }

    @Test
    fun resolvesArchiveCandidatesAndDeduplicatesUrls() = withTempDir { root ->
        File(root, "download.rule").writeText("downloads")
        val manifest = manifest(capabilities = setOf(SourceCapability.DOWNLOAD_RESOLUTION), entrypoints = mapOf("downloadResolution" to "download.rule"))
        val runtime = runtime("<a class='download' href='/files/book.zip'>one</a><a class='download' href='/files/book.zip'>duplicate</a><a class='download' href='/files/book2.zip'>two</a>", DeclarativeEntrypointDecoder { DeclarativeEntrypoint.DownloadResolution(items = RepeatedFields(item = ".download", link = "@href"), type = DownloadType.ARCHIVE) })
        val results = runtime.resolveDownloads(manifest, root, "https://example.org/book")
        assertEquals(2, results.size); assertTrue(results.all { it.type == DownloadType.ARCHIVE }); assertEquals("https://example.org/files/book.zip", results[0].url)
    }

    @Test
    fun refusesEntrypointOutsidePackageDirectory() = withTempDir { root ->
        val manifest = manifest(entrypoints = mapOf("seriesLookup" to "../outside.rule")); val runtime = runtime("<h1>x</h1>", DeclarativeEntrypointDecoder { DeclarativeEntrypoint.SeriesLookup("h1") })
        assertThrows(PluginSandboxViolation::class.java) { runtime.resolveSeries(manifest, root, "https://example.org/cycle") }
    }

    @Test
    fun capabilityMustBeDeclared() = withTempDir { root ->
        File(root, "series.rule").writeText("series")
        val manifest = manifest(capabilities = setOf(SourceCapability.DOWNLOAD_RESOLUTION), entrypoints = mapOf("seriesLookup" to "series.rule")); val runtime = runtime("<h1>x</h1>", DeclarativeEntrypointDecoder { DeclarativeEntrypoint.SeriesLookup("h1") })
        assertThrows(PluginSandboxViolation::class.java) { runtime.resolveSeries(manifest, root, "https://example.org/cycle") }
    }

    private fun runtime(body: String, decoder: DeclarativeEntrypointDecoder): DeclarativePluginRuntime = DeclarativePluginRuntime(PluginSandbox(PluginHttpTransport { request, _ -> PluginHttpResponse(200, request.url, body) }), decoder)
    private fun manifest(capabilities: Set<SourceCapability> = setOf(SourceCapability.SERIES_LOOKUP), entrypoints: Map<String, String>) = PluginPackageManifest(id = "declarative-test", name = "Declarative test", version = 1, apiVersion = SOURCE_PLUGIN_API_VERSION, runtime = PluginRuntime.DECLARATIVE, hosts = setOf("example.org"), capabilities = capabilities, permissions = PluginPermissions(networkHosts = setOf("example.org")), entrypoints = entrypoints)
    private inline fun withTempDir(block: (File) -> Unit) { val root = createTempDirectory("audoiboo-declarative-runtime-test-").toFile(); try { block(root) } finally { root.deleteRecursively() } }
}
