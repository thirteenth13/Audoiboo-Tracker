package org.audoiboo.tracker.plugin

import kotlin.math.max
import kotlin.math.min

/** Lightweight canonical snapshot used by the source matcher without depending on Room entities. */
data class CanonicalBookMatchInput(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val number: Double? = null
)

data class CanonicalSeriesMatchInput(
    val id: String,
    val title: String,
    val authors: List<String> = emptyList(),
    val books: List<CanonicalBookMatchInput> = emptyList()
)

enum class MatchDisposition {
    AUTO_ACCEPT,
    REVIEW,
    REJECT
}

data class IdentityMatch<T>(
    val value: T,
    val confidence: Float,
    val disposition: MatchDisposition,
    val evidence: List<String>
)

/**
 * Explainable, conservative matcher for linking observations from different source plugins.
 * Auto-linking requires both high confidence and enough separation from the runner-up.
 */
object SourceIdentityMatcher {
    const val AUTO_ACCEPT_THRESHOLD = 0.95f
    const val REVIEW_THRESHOLD = 0.70f
    private const val SERIES_AUTO_MARGIN = 0.08f
    private const val BOOK_AUTO_MARGIN = 0.05f

    fun bestSeriesMatch(
        incoming: SourceSeries,
        incomingBooks: List<SourceBook>,
        candidates: List<CanonicalSeriesMatchInput>
    ): IdentityMatch<CanonicalSeriesMatchInput>? {
        val ranked = candidates.map { candidate -> scoreSeries(incoming, incomingBooks, candidate) }
            .sortedByDescending { it.confidence }
        val best = ranked.firstOrNull() ?: return null
        if (best.confidence < REVIEW_THRESHOLD) return best.copy(disposition = MatchDisposition.REJECT)
        val second = ranked.getOrNull(1)?.confidence ?: 0f
        return if (best.confidence >= AUTO_ACCEPT_THRESHOLD && best.confidence - second >= SERIES_AUTO_MARGIN) {
            best.copy(disposition = MatchDisposition.AUTO_ACCEPT)
        } else {
            best.copy(disposition = MatchDisposition.REVIEW)
        }
    }

    fun bestBookMatch(
        incoming: SourceBook,
        candidates: List<CanonicalBookMatchInput>
    ): IdentityMatch<CanonicalBookMatchInput>? {
        val ranked = candidates.map { candidate -> scoreBook(incoming, candidate) }
            .sortedByDescending { it.confidence }
        val best = ranked.firstOrNull() ?: return null
        if (best.confidence < REVIEW_THRESHOLD) return best.copy(disposition = MatchDisposition.REJECT)
        val second = ranked.getOrNull(1)?.confidence ?: 0f
        return if (best.confidence >= AUTO_ACCEPT_THRESHOLD && best.confidence - second >= BOOK_AUTO_MARGIN) {
            best.copy(disposition = MatchDisposition.AUTO_ACCEPT)
        } else {
            best.copy(disposition = MatchDisposition.REVIEW)
        }
    }

    fun normalizeTitle(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[’'`\u00B4]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun normalizeAuthor(value: String): String = normalizeTitle(value)
        .replace(Regex("\\b(автор|author)\\b"), "")
        .trim()

    private fun scoreSeries(
        incoming: SourceSeries,
        incomingBooks: List<SourceBook>,
        candidate: CanonicalSeriesMatchInput
    ): IdentityMatch<CanonicalSeriesMatchInput> {
        val evidence = mutableListOf<String>()
        val incomingTitle = normalizeTitle(incoming.title)
        val candidateTitle = normalizeTitle(candidate.title)
        val titleSimilarity = tokenSimilarity(incomingTitle, candidateTitle)
        var score = when {
            incomingTitle.isNotBlank() && incomingTitle == candidateTitle -> {
                evidence += "exact series title"
                0.78f
            }
            titleSimilarity >= 0.85f -> {
                evidence += "similar series title"
                0.62f * titleSimilarity
            }
            else -> 0.45f * titleSimilarity
        }

        val incomingAuthors = (incoming.authors.map { it.name } + incomingBooks.flatMap { book -> book.authors.map { it.name } })
            .map(::normalizeAuthor).filter { it.isNotBlank() }.toSet()
        val candidateAuthors = candidate.authors.map(::normalizeAuthor).filter { it.isNotBlank() }.toSet()
        if (incomingAuthors.isNotEmpty() && candidateAuthors.isNotEmpty()) {
            if (incomingAuthors.intersect(candidateAuthors).isNotEmpty()) {
                score += 0.12f
                evidence += "author overlap"
            } else {
                score -= 0.18f
                evidence += "conflicting authors"
            }
        }

        val incomingBookTitles = incomingBooks.map { normalizeTitle(it.title) }.filter { it.isNotBlank() }.toSet()
        val candidateBookTitles = candidate.books.map { normalizeTitle(it.title) }.filter { it.isNotBlank() }.toSet()
        if (incomingBookTitles.isNotEmpty() && candidateBookTitles.isNotEmpty()) {
            val overlap = incomingBookTitles.intersect(candidateBookTitles).size.toFloat() /
                min(incomingBookTitles.size, candidateBookTitles.size).coerceAtLeast(1)
            if (overlap > 0f) {
                score += 0.20f * overlap
                evidence += "book overlap ${"%.2f".format(overlap)}"
            }
        }

        val confidence = score.coerceIn(0f, 1f)
        val disposition = when {
            confidence >= AUTO_ACCEPT_THRESHOLD -> MatchDisposition.AUTO_ACCEPT
            confidence >= REVIEW_THRESHOLD -> MatchDisposition.REVIEW
            else -> MatchDisposition.REJECT
        }
        return IdentityMatch(candidate, confidence, disposition, evidence)
    }

    private fun scoreBook(
        incoming: SourceBook,
        candidate: CanonicalBookMatchInput
    ): IdentityMatch<CanonicalBookMatchInput> {
        val evidence = mutableListOf<String>()
        val incomingTitle = normalizeTitle(incoming.title)
        val candidateTitle = normalizeTitle(candidate.title)
        val titleSimilarity = tokenSimilarity(incomingTitle, candidateTitle)
        var score = when {
            incomingTitle.isNotBlank() && incomingTitle == candidateTitle -> {
                evidence += "exact book title"
                0.96f
            }
            titleSimilarity >= 0.9f -> {
                evidence += "similar book title"
                0.78f * titleSimilarity
            }
            else -> 0.58f * titleSimilarity
        }

        val incomingAuthors = incoming.authors.map { normalizeAuthor(it.name) }.filter { it.isNotBlank() }.toSet()
        val candidateAuthors = candidate.authors.map(::normalizeAuthor).filter { it.isNotBlank() }.toSet()
        if (incomingAuthors.isNotEmpty() && candidateAuthors.isNotEmpty()) {
            if (incomingAuthors.intersect(candidateAuthors).isNotEmpty()) {
                score += 0.08f
                evidence += "author overlap"
            } else {
                score -= 0.12f
                evidence += "conflicting authors"
            }
        }

        if (incoming.seriesNumber != null && candidate.number != null) {
            if (kotlin.math.abs(incoming.seriesNumber - candidate.number) < 0.01) {
                score += 0.05f
                evidence += "volume number agrees"
            } else {
                score -= 0.08f
                evidence += "volume number conflicts"
            }
        }

        val confidence = score.coerceIn(0f, 1f)
        val disposition = when {
            confidence >= AUTO_ACCEPT_THRESHOLD -> MatchDisposition.AUTO_ACCEPT
            confidence >= REVIEW_THRESHOLD -> MatchDisposition.REVIEW
            else -> MatchDisposition.REJECT
        }
        return IdentityMatch(candidate, confidence, disposition, evidence)
    }

    private fun tokenSimilarity(left: String, right: String): Float {
        if (left.isBlank() || right.isBlank()) return 0f
        if (left == right) return 1f
        val a = left.split(' ').filter { it.isNotBlank() }.toSet()
        val b = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        val jaccard = if (union == 0f) 0f else intersection / union
        val containment = intersection / max(a.size, b.size).toFloat()
        return max(jaccard, containment)
    }
}
