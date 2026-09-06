package org.audoiboo.tracker.plugin

import kotlin.math.max
import kotlin.math.min

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

enum class MatchDisposition { AUTO_ACCEPT, REVIEW, REJECT }

data class IdentityMatch<T>(
    val value: T,
    val confidence: Float,
    val disposition: MatchDisposition,
    val evidence: List<String>
)

object SourceIdentityMatcher {
    const val AUTO_ACCEPT_THRESHOLD = 0.95f
    const val REVIEW_THRESHOLD = 0.70f
    private const val SERIES_AUTO_MARGIN = 0.08f
    private const val BOOK_AUTO_MARGIN = 0.05f

    fun bestSeriesMatch(incoming: SourceSeries, incomingBooks: List<SourceBook>, candidates: List<CanonicalSeriesMatchInput>): IdentityMatch<CanonicalSeriesMatchInput>? {
        val ranked = candidates.map { scoreSeries(incoming, incomingBooks, it) }.sortedByDescending { it.confidence }
        val best = ranked.firstOrNull() ?: return null
        if (best.confidence < REVIEW_THRESHOLD) return best.copy(disposition = MatchDisposition.REJECT)
        val second = ranked.getOrNull(1)?.confidence ?: 0f
        return if (best.confidence >= AUTO_ACCEPT_THRESHOLD && best.confidence - second >= SERIES_AUTO_MARGIN) best.copy(disposition = MatchDisposition.AUTO_ACCEPT)
        else best.copy(disposition = MatchDisposition.REVIEW)
    }

    fun bestBookMatch(incoming: SourceBook, candidates: List<CanonicalBookMatchInput>): IdentityMatch<CanonicalBookMatchInput>? {
        val ranked = candidates.map { scoreBook(incoming, it) }.sortedByDescending { it.confidence }
        val best = ranked.firstOrNull() ?: return null
        if (best.confidence < REVIEW_THRESHOLD) return best.copy(disposition = MatchDisposition.REJECT)
        val second = ranked.getOrNull(1)?.confidence ?: 0f
        return if (best.confidence >= AUTO_ACCEPT_THRESHOLD && best.confidence - second >= BOOK_AUTO_MARGIN) best.copy(disposition = MatchDisposition.AUTO_ACCEPT)
        else best.copy(disposition = MatchDisposition.REVIEW)
    }

    fun normalizeTitle(value: String): String = value
        .lowercase()
        .replace('ё', 'е')
        .replace(Regex("[’'`\\u00B4]"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    fun normalizeAuthor(value: String): String = normalizeTitle(value)
        .replace(Regex("\\b(автор|author)\\b"), "")
        .trim()
        .split(' ')
        .filter { it.isNotBlank() }
        .sorted()
        .joinToString(" ")

    private fun scoreSeries(incoming: SourceSeries, incomingBooks: List<SourceBook>, candidate: CanonicalSeriesMatchInput): IdentityMatch<CanonicalSeriesMatchInput> {
        val evidence = mutableListOf<String>()
        val incomingTitle = normalizeTitle(incoming.title)
        val candidateTitle = normalizeTitle(candidate.title)
        val exactSeriesTitle = incomingTitle.isNotBlank() && incomingTitle == candidateTitle
        val titleSimilarity = tokenSimilarity(incomingTitle, candidateTitle)
        var score = when {
            exactSeriesTitle -> { evidence += "exact series title"; 0.78f }
            titleSimilarity >= 0.85f -> { evidence += "similar series title"; 0.62f * titleSimilarity }
            else -> 0.45f * titleSimilarity
        }

        val incomingAuthors = (incoming.authors.map { it.name } + incomingBooks.flatMap { b -> b.authors.map { it.name } })
            .map(::normalizeAuthor).filter { it.isNotBlank() }.toSet()
        val candidateAuthors = candidate.authors.map(::normalizeAuthor).filter { it.isNotBlank() }.toSet()
        val authorOverlap = incomingAuthors.isNotEmpty() && candidateAuthors.isNotEmpty() && incomingAuthors.intersect(candidateAuthors).isNotEmpty()
        if (incomingAuthors.isNotEmpty() && candidateAuthors.isNotEmpty()) {
            if (authorOverlap) {
                score += 0.12f
                evidence += "author overlap"
                if (exactSeriesTitle) {
                    score = max(score, 0.97f)
                    evidence += "exact series title + author overlap"
                }
            } else {
                score -= 0.18f
                evidence += "conflicting authors"
                evidence += "author details incoming=${incomingAuthors.sorted()} canonical=${candidateAuthors.sorted()}"
            }
        }

        val incomingBookTitles = incomingBooks.map { normalizeTitle(it.title) }.filter { it.isNotBlank() }.toSet()
        val candidateBookTitles = candidate.books.map { normalizeTitle(it.title) }.filter { it.isNotBlank() }.toSet()
        if (incomingBookTitles.isNotEmpty() && candidateBookTitles.isNotEmpty()) {
            val overlap = incomingBookTitles.intersect(candidateBookTitles).size.toFloat() / min(incomingBookTitles.size, candidateBookTitles.size).coerceAtLeast(1)
            if (overlap > 0f) { score += 0.20f * overlap; evidence += "book overlap ${"%.2f".format(overlap)}" }
        }

        val relaxedOverlap = relaxedSeriesBookOverlap(incomingBooks, candidate.books, candidateTitle)
        if (relaxedOverlap.count > 0) evidence += "relaxed book overlap ${relaxedOverlap.count}/${relaxedOverlap.denominator}"
        if (exactSeriesTitle && relaxedOverlap.count >= 2 && relaxedOverlap.ratio >= 0.30f) {
            score = max(score, 0.97f)
            evidence += "exact title + strong book overlap overrides author conflict"
        } else if (relaxedOverlap.ratio > 0f) score += 0.12f * relaxedOverlap.ratio

        val confidence = score.coerceIn(0f, 1f)
        val disposition = when {
            confidence >= AUTO_ACCEPT_THRESHOLD -> MatchDisposition.AUTO_ACCEPT
            confidence >= REVIEW_THRESHOLD -> MatchDisposition.REVIEW
            else -> MatchDisposition.REJECT
        }
        return IdentityMatch(candidate, confidence, disposition, evidence)
    }

    private data class RelaxedOverlap(val count: Int, val denominator: Int) {
        val ratio: Float get() = if (denominator <= 0) 0f else count.toFloat() / denominator.toFloat()
    }

    private fun relaxedSeriesBookOverlap(incomingBooks: List<SourceBook>, candidateBooks: List<CanonicalBookMatchInput>, normalizedSeriesTitle: String): RelaxedOverlap {
        if (incomingBooks.isEmpty() || candidateBooks.isEmpty()) return RelaxedOverlap(0, 0)
        val used = linkedSetOf<Int>(); var matches = 0
        incomingBooks.forEach { incoming ->
            val incomingNormalized = normalizeBookForSeries(incoming.title, normalizedSeriesTitle, incoming.authors.map { it.name })
            val best = candidateBooks.indices.filterNot { it in used }.map { index ->
                val candidate = candidateBooks[index]
                val candidateNormalized = normalizeBookForSeries(candidate.title, normalizedSeriesTitle, candidate.authors)
                val titleScore = tokenSimilarity(incomingNormalized, candidateNormalized)
                val numberAgrees = incoming.seriesNumber != null && candidate.number != null && kotlin.math.abs(incoming.seriesNumber - candidate.number) < 0.01
                Triple(index, titleScore, numberAgrees)
            }.maxByOrNull { (_, titleScore, numberAgrees) -> titleScore + if (numberAgrees) 0.35f else 0f } ?: return@forEach
            val (_, titleScore, numberAgrees) = best
            if (titleScore >= 0.60f || (numberAgrees && titleScore >= 0.25f)) { used += best.first; matches++ }
        }
        return RelaxedOverlap(matches, min(incomingBooks.size, candidateBooks.size))
    }

    private fun normalizeBookForSeries(value: String, normalizedSeriesTitle: String, authors: List<String> = emptyList()): String {
        var tokens = normalizeTitle(value).split(' ').filter { it.isNotBlank() }
        val removable = buildSet {
            normalizedSeriesTitle.split(' ').filter { it.length > 1 }.forEach(::add)
            authors.flatMap { normalizeAuthor(it).split(' ') }.filter { it.length > 1 }.forEach(::add)
        }
        tokens = tokens.filterNot { it in removable }
        return tokens.joinToString(" ")
            .replace(Regex("\\b(книга|том|часть|частина|book|volume|vol)\\b"), " ")
            .replace(Regex("\\b\\d+(?:[.,]\\d+)?\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun scoreBook(incoming: SourceBook, candidate: CanonicalBookMatchInput): IdentityMatch<CanonicalBookMatchInput> {
        val evidence = mutableListOf<String>()
        val incomingTitle = normalizeTitle(incoming.title)
        val candidateTitle = normalizeTitle(candidate.title)
        val rawSimilarity = tokenSimilarity(incomingTitle, candidateTitle)
        val seriesTitle = normalizeTitle(incoming.seriesTitle.orEmpty())
        val cleanedIncoming = normalizeBookForSeries(incoming.title, seriesTitle, incoming.authors.map { it.name })
        val cleanedCandidate = normalizeBookForSeries(candidate.title, seriesTitle, candidate.authors)
        val cleanedSimilarity = tokenSimilarity(cleanedIncoming, cleanedCandidate)
        val numberKnown = incoming.seriesNumber != null && candidate.number != null
        val numberAgrees = numberKnown && kotlin.math.abs(incoming.seriesNumber!! - candidate.number!!) < 0.01
        val numberConflicts = numberKnown && !numberAgrees
        val decoratedExact = !numberConflicts && cleanedIncoming.isNotBlank() && cleanedIncoming == cleanedCandidate && incomingTitle != candidateTitle
        val titleSimilarity = max(rawSimilarity, cleanedSimilarity)
        var score = when {
            incomingTitle.isNotBlank() && incomingTitle == candidateTitle -> { evidence += "exact book title"; 0.96f }
            decoratedExact -> { evidence += "decorated provider title resolves to canonical title"; 0.96f }
            titleSimilarity >= 0.9f -> { evidence += "similar book title"; 0.78f * titleSimilarity }
            else -> 0.58f * titleSimilarity
        }
        val incomingAuthors = incoming.authors.map { normalizeAuthor(it.name) }.filter { it.isNotBlank() }.toSet()
        val candidateAuthors = candidate.authors.map(::normalizeAuthor).filter { it.isNotBlank() }.toSet()
        if (incomingAuthors.isNotEmpty() && candidateAuthors.isNotEmpty()) {
            if (incomingAuthors.intersect(candidateAuthors).isNotEmpty()) { score += 0.08f; evidence += "author overlap" }
            else { score -= 0.12f; evidence += "conflicting authors"; evidence += "author details incoming=${incomingAuthors.sorted()} canonical=${candidateAuthors.sorted()}" }
        }
        if (numberKnown) {
            if (numberAgrees) {
                score += 0.05f
                evidence += "volume number agrees"
            } else {
                evidence += "volume number conflicts"
                if (incomingTitle != candidateTitle) score = min(score, REVIEW_THRESHOLD - 0.01f)
                else score -= 0.08f
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
        val a = left.split(' ').filter { it.isNotBlank() }.toSet(); val b = right.split(' ').filter { it.isNotBlank() }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size.toFloat(); val union = a.union(b).size.toFloat()
        val jaccard = if (union == 0f) 0f else intersection / union
        val containment = intersection / max(a.size, b.size).toFloat()
        return max(jaccard, containment)
    }
}
