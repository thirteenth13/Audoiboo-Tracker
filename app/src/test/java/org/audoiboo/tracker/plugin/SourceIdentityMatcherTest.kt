package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceIdentityMatcherTest {
    @Test
    fun exactSeriesTitleWithBookOverlapAutoLinks() {
        val incoming = SourceSeries(sourceId = "other", url = "https://other.example/series/1", title = "Звездная Кровь")
        val books = listOf(sourceBook("other", "Книга первая", "Роман Прокофьев"), sourceBook("other", "Книга вторая", "Роман Прокофьев"))
        val candidate = CanonicalSeriesMatchInput(
            id = "canonical", title = "Звёздная кровь", authors = listOf("Роман Прокофьев"),
            books = listOf(CanonicalBookMatchInput("1", "Книга первая", listOf("Роман Прокофьев")), CanonicalBookMatchInput("2", "Книга вторая", listOf("Роман Прокофьев")))
        )
        val match = SourceIdentityMatcher.bestSeriesMatch(incoming, books, listOf(candidate))!!
        assertEquals("canonical", match.value.id)
        assertEquals(MatchDisposition.AUTO_ACCEPT, match.disposition)
        assertTrue(match.confidence >= SourceIdentityMatcher.AUTO_ACCEPT_THRESHOLD)
    }

    @Test
    fun reversedAuthorNameWithExactSeriesTitleAutoLinks() {
        val incoming = SourceSeries("audioboo", "https://audioboo.example/stellar", "Стеллар", authors = listOf(SourceAuthor("Прокофьев Роман")))
        val candidate = CanonicalSeriesMatchInput("catalog-stellar", "Стеллар", authors = listOf("Роман Прокофьев"))
        val match = SourceIdentityMatcher.bestSeriesMatch(incoming, emptyList(), listOf(candidate))!!
        assertEquals(MatchDisposition.AUTO_ACCEPT, match.disposition)
        assertTrue(match.evidence.contains("author overlap"))
        assertTrue(match.evidence.contains("exact series title + author overlap"))
    }

    @Test
    fun exactSeriesWithStrongVolumeOverlapOverridesNoisyProviderAuthor() {
        val incoming = SourceSeries(sourceId = "audioboo", url = "https://audioboo.example/stellar", title = "Стеллар", authors = listOf(SourceAuthor("Исполнитель сайта")))
        val books = listOf(
            sourceBook("audioboo", "Стеллар. Инкарнатор", "Исполнитель сайта").copy(seriesNumber = 1.0),
            sourceBook("audioboo", "Стеллар. Трибут", "Исполнитель сайта").copy(seriesNumber = 2.0),
            sourceBook("audioboo", "Стеллар. Архонт", "Исполнитель сайта").copy(seriesNumber = 3.0)
        )
        val candidate = CanonicalSeriesMatchInput(
            id = "catalog-stellar", title = "Стеллар", authors = listOf("Роман Прокофьев"),
            books = listOf(
                CanonicalBookMatchInput("1", "Инкарнатор", listOf("Роман Прокофьев"), 1.0),
                CanonicalBookMatchInput("2", "Трибут", listOf("Роман Прокофьев"), 2.0),
                CanonicalBookMatchInput("3", "Архонт", listOf("Роман Прокофьев"), 3.0)
            )
        )
        val match = SourceIdentityMatcher.bestSeriesMatch(incoming, books, listOf(candidate))!!
        assertEquals(MatchDisposition.AUTO_ACCEPT, match.disposition)
        assertTrue(match.confidence >= 0.95f)
        assertTrue("conflicting authors" in match.evidence)
        assertTrue(match.evidence.any { it.startsWith("author details incoming=") })
        assertTrue(match.evidence.any { it.contains("strong book overlap overrides author conflict") })
    }

    @Test
    fun sameSeriesTitleWithoutSupportingEvidenceNeedsReview() {
        val incoming = SourceSeries("other", url = "https://other.example/s", title = "Хроники")
        val candidate = CanonicalSeriesMatchInput("canonical", "Хроники")
        val match = SourceIdentityMatcher.bestSeriesMatch(incoming, emptyList(), listOf(candidate))!!
        assertEquals(MatchDisposition.REVIEW, match.disposition)
    }

    @Test
    fun conflictingAuthorPreventsAutomaticSeriesMerge() {
        val incoming = SourceSeries("other", url = "https://other.example/s", title = "Звездная Кровь")
        val books = listOf(sourceBook("other", "Белый Дьявол", "Другой Автор"))
        val candidate = CanonicalSeriesMatchInput(id = "canonical", title = "Звездная Кровь", authors = listOf("Роман Прокофьев"), books = listOf(CanonicalBookMatchInput("1", "Совсем другая книга", listOf("Роман Прокофьев"))))
        val match = SourceIdentityMatcher.bestSeriesMatch(incoming, books, listOf(candidate))!!
        assertTrue(match.disposition != MatchDisposition.AUTO_ACCEPT)
        assertTrue("conflicting authors" in match.evidence)
    }

    @Test
    fun ambiguousStrongSeriesMatchesAreNotAutoLinked() {
        val incoming = SourceSeries("other", url = "https://other.example/s", title = "Система")
        val books = listOf(sourceBook("other", "Старт", "Автор"))
        val first = CanonicalSeriesMatchInput("one", "Система", listOf("Автор"), listOf(CanonicalBookMatchInput("1", "Старт", listOf("Автор"))))
        val second = first.copy(id = "two")
        val match = SourceIdentityMatcher.bestSeriesMatch(incoming, books, listOf(first, second))!!
        assertEquals(MatchDisposition.REVIEW, match.disposition)
    }

    @Test
    fun exactBookTitleAutoLinksInsideKnownSeries() {
        val incoming = sourceBook("other", "Прозрачные дороги", "Роман Прокофьев").copy(seriesNumber = 10.0)
        val candidate = CanonicalBookMatchInput("book-10", "Прозрачные дороги", listOf("Роман Прокофьев"), 10.0)
        val match = SourceIdentityMatcher.bestBookMatch(incoming, listOf(candidate))!!
        assertEquals("book-10", match.value.id)
        assertEquals(MatchDisposition.AUTO_ACCEPT, match.disposition)
    }

    private fun sourceBook(sourceId: String, title: String, author: String) = SourceBook(
        sourceId = sourceId, url = "https://example.org/${title.hashCode()}", title = title, authors = listOf(SourceAuthor(author))
    )
}
