package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PinnedSeriesDiscoveryPolicyTest {
    private fun finding(
        sourceId: String,
        url: String,
        confidence: Float,
        title: String = "Стеллар",
        bookCount: Int = 1
    ): SeriesDiscoveryFinding {
        val series = SourceSeries(
            sourceId = sourceId,
            url = url,
            title = title
        )
        val books = (1..bookCount).map { index ->
            SourceBook(
                sourceId = sourceId,
                url = "$url/book-$index",
                title = "Книга $index",
                seriesTitle = title,
                seriesNumber = index.toDouble()
            )
        }
        return SeriesDiscoveryFinding(
            sourceId = sourceId,
            series = series,
            books = books,
            confidence = confidence,
            disposition = MatchDisposition.AUTO_ACCEPT,
            evidence = emptyList()
        )
    }

    @Test
    fun pinnedProviderWinsOverHigherConfidenceBetterCoverageDiscovery() {
        val pinnedAudioboo = finding(
            sourceId = "audioboo",
            url = "https://audioboo.org/xfsearch/cikl/stellyar-pinned/",
            confidence = 0.72f,
            bookCount = 2
        )
        val discoveredAudioboo = finding(
            sourceId = "audioboo",
            url = "https://audioboo.org/xfsearch/cikl/stellyar-auto/",
            confidence = 0.99f,
            bookCount = 19
        )
        val discoveredBaza = finding(
            sourceId = "baza-knig",
            url = "https://baza-knig.info/series/stellyar",
            confidence = 0.96f,
            bookCount = 10
        )

        val result = PinnedSeriesDiscoveryPolicy.merge(
            pinned = listOf(pinnedAudioboo),
            discovered = listOf(discoveredAudioboo, discoveredBaza)
        )

        assertEquals(2, result.size)
        assertSame(pinnedAudioboo, result[0])
        assertSame(discoveredBaza, result[1])
    }

    @Test
    fun eachPinnedProviderSuppressesOnlyItsOwnAutomaticCandidate() {
        val pinnedAudioboo = finding("audioboo", "https://audioboo.org/pinned", 0.80f)
        val pinnedLis = finding("lis10book", "https://lis10book.com/pinned", 0.81f)
        val discoveredAudioboo = finding("audioboo", "https://audioboo.org/auto", 0.99f)
        val discoveredLis = finding("lis10book", "https://lis10book.com/auto", 0.98f)
        val discoveredBaza = finding("baza-knig", "https://baza-knig.info/auto", 0.97f)

        val result = PinnedSeriesDiscoveryPolicy.merge(
            pinned = listOf(pinnedAudioboo, pinnedLis),
            discovered = listOf(discoveredAudioboo, discoveredLis, discoveredBaza)
        )

        assertEquals(listOf(pinnedAudioboo, pinnedLis, discoveredBaza), result)
    }

    @Test
    fun noPinnedFindingsPreservesAutomaticDiscoveryOrder() {
        val first = finding("baza-knig", "https://baza-knig.info/one", 0.91f)
        val second = finding("audioboo", "https://audioboo.org/two", 0.90f)
        val discovered = listOf(first, second)

        val result = PinnedSeriesDiscoveryPolicy.merge(emptyList(), discovered)

        assertSame(discovered, result)
    }
}
