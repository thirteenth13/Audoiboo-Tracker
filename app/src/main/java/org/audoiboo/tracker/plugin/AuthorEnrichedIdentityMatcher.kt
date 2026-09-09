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
        val incomingNames = incoming.authors.map { it.name }
        val expandedIncoming = resolver.expandForMatching(incomingNames)
        val enrichedIncoming = if (expandedIncoming.isEmpty()) incoming else incoming.copy(
            authors = expandedIncoming.map(::SourceAuthor)
        )

        val enrichedCandidates = candidates.map { candidate ->
            val expanded = resolver.expandForMatching(candidate.authors)
            if (expanded.isEmpty()) candidate else candidate.copy(authors = expanded)
        }
        return SourceIdentityMatcher.bestBookMatch(enrichedIncoming, enrichedCandidates)
    }
}
