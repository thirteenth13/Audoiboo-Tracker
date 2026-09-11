package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorEnrichedIdentityMatcherConsensusTest {
    @Test
    fun borrowsSharedCanonicalAuthorWhenProviderBookAuthorIsMissing() {
        val incoming = SourceBook(
            sourceId = "audioboo",
            url = "https://audioboo.example/stellar-9",
            title = "Стеллар 9 — Прометей аудиокнига",
            authors = emptyList(),
            seriesTitle = "Стеллар",
            seriesNumber = 9.0
        )
        val candidates = listOf(
            CanonicalBookMatchInput("b8", "Чужая игра", listOf("Роман Прокофьев"), 8.0),
            CanonicalBookMatchInput("b9", "Прометей", listOf("Роман Прокофьев"), 9.0),
            CanonicalBookMatchInput("b10", "Следующая книга", listOf("Роман Прокофьев"), 10.0)
        )

        val prepared = AuthorEnrichedIdentityMatcher.withConsensusCandidateAuthors(incoming, candidates)
        val match = SourceIdentityMatcher.bestBookMatch(prepared, candidates)!!

        assertEquals(listOf("Роман Прокофьев"), prepared.authors.map { it.name })
        assertEquals("b9", match.value.id)
        assertEquals(MatchDisposition.AUTO_ACCEPT, match.disposition)
    }

    @Test
    fun doesNotBorrowAuthorWhenCanonicalCandidatesDisagree() {
        val incoming = SourceBook(
            sourceId = "provider",
            url = "https://provider.example/book/9",
            title = "Совсем другая книга",
            authors = emptyList(),
            seriesTitle = "Сборник",
            seriesNumber = 9.0
        )
        val candidates = listOf(
            CanonicalBookMatchInput("a", "Прометей", listOf("Роман Прокофьев"), 9.0),
            CanonicalBookMatchInput("b", "Другая линия", listOf("Другой Автор"), 9.0)
        )

        val prepared = AuthorEnrichedIdentityMatcher.withConsensusCandidateAuthors(incoming, candidates)
        val match = SourceIdentityMatcher.bestBookMatch(prepared, candidates)!!

        assertTrue(prepared.authors.isEmpty())
        assertTrue(match.disposition != MatchDisposition.AUTO_ACCEPT)
    }

    @Test
    fun neverOverridesExplicitProviderAuthor() {
        val incoming = SourceBook(
            sourceId = "provider",
            url = "https://provider.example/prometey",
            title = "Прометей",
            authors = listOf(SourceAuthor("Нина Световидова")),
            seriesTitle = "Стеллар",
            seriesNumber = 9.0
        )
        val candidates = listOf(
            CanonicalBookMatchInput("b9", "Прометей", listOf("Роман Прокофьев"), 9.0)
        )

        val prepared = AuthorEnrichedIdentityMatcher.withConsensusCandidateAuthors(incoming, candidates)
        val match = SourceIdentityMatcher.bestBookMatch(prepared, candidates)!!

        assertEquals(listOf("Нина Световидова"), prepared.authors.map { it.name })
        assertTrue(match.disposition != MatchDisposition.AUTO_ACCEPT)
    }
}
