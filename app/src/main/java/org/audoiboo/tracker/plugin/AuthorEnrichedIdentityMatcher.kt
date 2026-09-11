package org.audoiboo.tracker.plugin

import android.content.Context

/**
 * Suspend bridge used by sync/discovery code before the pure SourceIdentityMatcher.
 * It enriches both sides with cached/FantLab/Flibusta author aliases, while keeping
 * SourceIdentityMatcher itself synchronous and independent from Room/networking.
 */
object AuthorEnrichedIdentityMatcher {
    suspend fun bestBookMatch(
        context: Context,
        incoming: SourceBook,
        candidates: List<CanonicalBookMatchInput>
    ): IdentityMatch<CanonicalBookMatchInput>? {
        if (candidates.isEmpty()) return null
        val resolver = AuthorAliasResolver.forContext(context)
        return bestBookMatch(resolver, incoming, candidates)
    }

    internal suspend fun bestBookMatch(
        resolver: AuthorAliasResolver,
        incoming: SourceBook,
        candidates: List<CanonicalBookMatchInput>
    ): IdentityMatch<CanonicalBookMatchInput>? {
        val preparedIncoming = withConsensusCandidateAuthors(incoming, candidates)
        val incomingNames = preparedIncoming.authors.map { it.name }
        val expandedIncoming = resolver.expandForMatching(incomingNames)
        val enrichedIncoming = if (expandedIncoming.isEmpty()) preparedIncoming else preparedIncoming.copy(
            authors = expandedIncoming.map(::SourceAuthor)
        )

        val enrichedCandidates = candidates.map { candidate ->
            val expanded = resolver.expandForMatching(candidate.authors)
            if (expanded.isEmpty()) candidate else candidate.copy(authors = expanded)
        }
        return SourceIdentityMatcher.bestBookMatch(enrichedIncoming, enrichedCandidates)
    }

    /**
     * Some providers omit the author on individual book pages even when the canonical series is
     * unambiguous. In that case only, borrow the canonical candidates' shared author identity for
     * matching. The SourceBook itself is not persisted with the borrowed author.
     *
     * This deliberately does nothing when candidate authors disagree, so an ordinal alone can
     * never manufacture an author match across mixed-author series.
     */
    internal fun withConsensusCandidateAuthors(
        incoming: SourceBook,
        candidates: List<CanonicalBookMatchInput>
    ): SourceBook {
        if (incoming.authors.any { it.name.isNotBlank() }) return incoming
        val authoredCandidates = candidates
            .map { candidate -> candidate.authors.map(String::trim).filter(String::isNotBlank) }
            .filter(List<String>::isNotEmpty)
        if (authoredCandidates.isEmpty()) return incoming

        val normalizedSets = authoredCandidates.map { authors ->
            authors.map(SourceIdentityMatcher::normalizeAuthor).filter(String::isNotBlank).toSet()
        }
        val consensus = normalizedSets.reduce { left, right -> left.intersect(right) }
        if (consensus.isEmpty()) return incoming

        val borrowed = authoredCandidates.flatten()
            .filter { SourceIdentityMatcher.normalizeAuthor(it) in consensus }
            .distinctBy(SourceIdentityMatcher::normalizeAuthor)
        if (borrowed.isEmpty()) return incoming
        return incoming.copy(authors = borrowed.map(::SourceAuthor))
    }
}
