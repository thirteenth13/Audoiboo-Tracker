package org.audoiboo.tracker.plugin

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadResolutionPlannerTest {
    private class FakeResolverPlugin(
        id: String,
        private val host: String,
        private val result: suspend (SourceBook) -> List<DownloadCandidate>
    ) : SourcePlugin, DownloadResolver {
        override val descriptor = SourceDescriptor(
            id = id,
            name = id,
            version = 1,
            hosts = setOf(host),
            capabilities = setOf(SourceCapability.DOWNLOAD_RESOLUTION)
        )

        override fun supports(url: String): Boolean = runCatching {
            java.net.URI(url).host.equals(host, ignoreCase = true)
        }.getOrDefault(false)

        override suspend fun resolveDownloads(book: SourceBook): List<DownloadCandidate> = result(book)
    }

    @Test
    fun fallsBackToNextMappedSourceWhenPrimaryHasNoPayload() = runBlocking {
        val primary = FakeResolverPlugin("primary", "primary.test") { emptyList() }
        val fallback = FakeResolverPlugin("fallback", "fallback.test") {
            listOf(DownloadCandidate(DownloadType.DIRECT_FILE, "https://fallback.test/book.mp3", priority = 5))
        }
        val planner = DownloadResolutionPlanner(SourcePluginRegistry(listOf(primary, fallback)))

        val result = planner.resolve(
            listOf(
                SourceBook("primary", url = "https://primary.test/book", title = "Book"),
                SourceBook("fallback", url = "https://fallback.test/book", title = "Book")
            )
        )

        assertEquals("fallback", result?.book?.sourceId)
        assertEquals("https://fallback.test/book.mp3", result?.candidate?.url)
    }

    @Test
    fun brokenPrimaryDoesNotBlockFallback() = runBlocking {
        val primary = FakeResolverPlugin("primary", "primary.test") { error("source offline") }
        val fallback = FakeResolverPlugin("fallback", "fallback.test") {
            listOf(DownloadCandidate(DownloadType.ARCHIVE, "https://fallback.test/book.zip"))
        }
        val planner = DownloadResolutionPlanner(SourcePluginRegistry(listOf(primary, fallback)))

        val result = planner.resolve(
            listOf(
                SourceBook("primary", url = "https://primary.test/book", title = "Book"),
                SourceBook("fallback", url = "https://fallback.test/book", title = "Book")
            )
        )

        assertEquals("fallback", result?.book?.sourceId)
    }

    @Test
    fun singleBazaTrackIsProvisionalWhenLaterSourceHasCompletePlaylist() = runBlocking {
        val baza = FakeResolverPlugin("baza-knig", "baza.test") {
            listOf(DownloadCandidate(DownloadType.DIRECT_FILE, "https://baza.test/01.mp3", priority = 10))
        }
        val lis = FakeResolverPlugin("lis10book", "lis.test") {
            listOf(
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://lis.test/01.mp3", priority = 10),
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://lis.test/02.mp3", priority = 9)
            )
        }
        val planner = DownloadResolutionPlanner(SourcePluginRegistry(listOf(baza, lis)))

        val result = planner.resolveAll(
            listOf(
                SourceBook("baza-knig", url = "https://baza.test/book", title = "Book"),
                SourceBook("lis10book", url = "https://lis.test/book", title = "Book")
            )
        )

        assertEquals(2, result.size)
        assertEquals("lis10book", result.first().book.sourceId)
    }

    @Test
    fun laterLargerPlaylistBeatsEarlierStrongPlaylist() = runBlocking {
        val first = FakeResolverPlugin("first", "first.test") {
            listOf(
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://first.test/01.mp3", priority = 10),
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://first.test/02.mp3", priority = 9)
            )
        }
        val second = FakeResolverPlugin("second", "second.test") {
            (1..5).map { index ->
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://second.test/$index.mp3", priority = 10 - index)
            }
        }
        val planner = DownloadResolutionPlanner(SourcePluginRegistry(listOf(first, second)))

        val result = planner.resolveAll(
            listOf(
                SourceBook("first", url = "https://first.test/book", title = "Book"),
                SourceBook("second", url = "https://second.test/book", title = "Book")
            )
        )

        assertEquals(5, result.size)
        assertEquals("second", result.first().book.sourceId)
    }

    @Test
    fun laterArchiveBeatsEarlierCompletePlaylist() = runBlocking {
        val playlist = FakeResolverPlugin("playlist", "playlist.test") {
            (1..8).map { index ->
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://playlist.test/$index.mp3", priority = 100)
            }
        }
        val archive = FakeResolverPlugin("archive", "archive.test") {
            listOf(DownloadCandidate(DownloadType.ARCHIVE, "https://archive.test/book.zip", priority = 1))
        }
        val planner = DownloadResolutionPlanner(SourcePluginRegistry(listOf(playlist, archive)))

        val result = planner.resolveAll(
            listOf(
                SourceBook("playlist", url = "https://playlist.test/book", title = "Book"),
                SourceBook("archive", url = "https://archive.test/book", title = "Book")
            )
        )

        assertEquals(1, result.size)
        assertEquals("archive", result.single().book.sourceId)
        assertEquals(DownloadType.ARCHIVE, result.single().candidate.type)
    }

    @Test
    fun equalQualityKeepsMappedSourceOrder() = runBlocking {
        val first = FakeResolverPlugin("first", "first.test") {
            listOf(
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://first.test/01.mp3", priority = 5),
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://first.test/02.mp3", priority = 5)
            )
        }
        val second = FakeResolverPlugin("second", "second.test") {
            listOf(
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://second.test/01.mp3", priority = 5),
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://second.test/02.mp3", priority = 5)
            )
        }
        val planner = DownloadResolutionPlanner(SourcePluginRegistry(listOf(first, second)))

        val result = planner.resolveAll(
            listOf(
                SourceBook("first", url = "https://first.test/book", title = "Book"),
                SourceBook("second", url = "https://second.test/book", title = "Book")
            )
        )

        assertEquals("first", result.first().book.sourceId)
    }

    @Test
    fun singlePlaylistProviderTrackIsReturnedWhenNoBetterFallbackExists() = runBlocking {
        val lis = FakeResolverPlugin("lis10book", "lis.test") {
            listOf(DownloadCandidate(DownloadType.DIRECT_FILE, "https://lis.test/only.mp3", priority = 10))
        }
        val unavailable = FakeResolverPlugin("fallback", "fallback.test") { emptyList() }
        val planner = DownloadResolutionPlanner(SourcePluginRegistry(listOf(lis, unavailable)))

        val result = planner.resolveAll(
            listOf(
                SourceBook("lis10book", url = "https://lis.test/book", title = "Book"),
                SourceBook("fallback", url = "https://fallback.test/book", title = "Book")
            )
        )

        assertEquals(1, result.size)
        assertEquals("lis10book", result.single().book.sourceId)
        assertEquals("https://lis.test/only.mp3", result.single().candidate.url)
    }

    @Test
    fun archiveWinsWithinOneSourceEvenWithLowerPriority() = runBlocking {
        val plugin = FakeResolverPlugin("source", "source.test") {
            listOf(
                DownloadCandidate(DownloadType.DIRECT_FILE, "https://source.test/book.mp3", priority = 100),
                DownloadCandidate(DownloadType.ARCHIVE, "https://source.test/book.zip", priority = 1)
            )
        }
        val result = DownloadResolutionPlanner(SourcePluginRegistry(listOf(plugin))).resolve(
            listOf(SourceBook("source", url = "https://source.test/book", title = "Book"))
        )

        assertEquals(DownloadType.ARCHIVE, result?.candidate?.type)
    }

    @Test
    fun ignoresUnknownOrUnsupportedSources() = runBlocking {
        val plugin = FakeResolverPlugin("source", "source.test") { emptyList() }
        val result = DownloadResolutionPlanner(SourcePluginRegistry(listOf(plugin))).resolve(
            listOf(SourceBook("missing", url = "https://missing.test/book", title = "Book"))
        )

        assertNull(result)
    }
}
