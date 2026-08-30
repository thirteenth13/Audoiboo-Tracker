package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class DeclarativeSourcePluginTest {
    @Test
    fun supportsOnlyDeclaredHosts() = withTempDir { root ->
        val plugin = plugin(root, setOf(SourceCapability.SERIES_LOOKUP))

        assertTrue(plugin.supports("https://example.org/cycle"))
        assertFalse(plugin.supports("https://evil.example/cycle"))
        assertFalse(plugin.supports("not a url"))
    }

    @Test
    fun seriesBooksFallbackToReferencesWithoutBookLookup() = withTempDir { root ->
        val plugin = plugin(root, setOf(SourceCapability.SERIES_LOOKUP))
        val series = SourceSeries(
            sourceId = "declarative-test",
            url = "https://example.org/cycle",
            title = "Cycle",
            authors = listOf(SourceAuthor("Author A")),
            books = listOf(SourceBookRef(url = "https://example.org/b1", title = "One", number = 1.0))
        )

        val books = runBlocking { plugin.loadSeriesBooks(series) }

        assertEquals(1, books.size)
        assertEquals("One", books.single().title)
        assertEquals("Cycle", books.single().seriesTitle)
        assertEquals(1.0, books.single().seriesNumber)
        assertEquals(listOf("Author A"), books.single().authors.map { it.name })
    }

    @Test
    fun completionQueriesIncludeNextVolumeFromTitles() {
        val books = (1..10).map { number ->
            SourceBook(
                sourceId = "declarative-test",
                url = "https://example.org/b$number",
                title = "Звездная Кровь ${number.toString().padStart(2, '0')}. Том"
            )
        }

        assertEquals(
            listOf("Звездная Кровь", "Звездная Кровь 11"),
            seriesCompletionQueries("Звездная Кровь", books)
        )
    }

    @Test
    fun completionQueriesPreferExplicitSeriesNumber() {
        val books = listOf(
            SourceBook(
                sourceId = "declarative-test",
                url = "https://example.org/b10",
                title = "Book without useful ordinal",
                seriesNumber = 10.0
            )
        )

        assertEquals(
            listOf("Cycle", "Cycle 11"),
            seriesCompletionQueries("Cycle", books)
        )
    }

    @Test
    fun rejectsForeignBookForDownloadResolution() = withTempDir { root ->
        val plugin = plugin(root, setOf(SourceCapability.DOWNLOAD_RESOLUTION))
        val foreign = SourceBook(sourceId = "other", url = "https://example.org/b1", title = "One")

        assertTrue(runBlocking { plugin.resolveDownloads(foreign) }.isEmpty())
    }

    @Test
    fun loadBookReturnsNullForForeignHostBeforeRuntime() = withTempDir { root ->
        val plugin = plugin(root, setOf(SourceCapability.BOOK_LOOKUP))

        assertNull(runBlocking { plugin.loadBook("https://evil.example/b1") })
    }

    private fun plugin(root: File, capabilities: Set<SourceCapability>): DeclarativeSourcePlugin {
        File(root, "noop.rule").writeText("noop")
        val manifest = PluginPackageManifest(
            id = "declarative-test",
            name = "Declarative test",
            version = 1,
            apiVersion = SOURCE_PLUGIN_API_VERSION,
            runtime = PluginRuntime.DECLARATIVE,
            hosts = setOf("example.org"),
            capabilities = capabilities,
            permissions = PluginPermissions(networkHosts = setOf("example.org")),
            entrypoints = capabilities.associate { capability ->
                when (capability) {
                    SourceCapability.SERIES_LOOKUP -> "seriesLookup" to "noop.rule"
                    SourceCapability.BOOK_LOOKUP -> "bookLookup" to "noop.rule"
                    SourceCapability.DOWNLOAD_RESOLUTION -> "downloadResolution" to "noop.rule"
                    else -> "unused-${capability.name}" to "noop.rule"
                }
            }
        )
        val sandbox = PluginSandbox(PluginHttpTransport { request, _ ->
            PluginHttpResponse(200, request.url, "<html></html>")
        })
        val runtime = DeclarativePluginRuntime(
            sandbox,
            DeclarativeEntrypointDecoder { DeclarativeEntrypoint.SeriesLookup("h1") }
        )
        return DeclarativeSourcePlugin(manifest, root, runtime)
    }

    private inline fun withTempDir(block: (File) -> Unit) {
        val root = createTempDirectory("audoiboo-source-plugin-test-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
