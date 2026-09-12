package org.audoiboo.tracker.ebook

import java.security.MessageDigest

data class TtsPlannedChunk(
    val globalIndex: Int,
    val chapterIndex: Int,
    val chapterChunkIndex: Int,
    val chapterTitle: String,
    val text: String,
    val checkpointKey: String,
)

data class TtsChapterPlan(
    val chapterIndex: Int,
    val title: String,
    val chunks: List<TtsPlannedChunk>,
)

data class TtsSynthesisPlan(
    val documentFingerprint: String,
    val language: String?,
    val chapters: List<TtsChapterPlan>,
) {
    val chunkCount: Int get() = chapters.sumOf { it.chunks.size }
    val chunks: List<TtsPlannedChunk> get() = chapters.flatMap { it.chunks }
}

object TtsSynthesisPlanner {
    const val PLAN_VERSION = 1

    fun build(
        document: BookDocument,
        targetChars: Int = TtsTextPipeline.DEFAULT_TARGET_CHARS,
        maxChars: Int = TtsTextPipeline.DEFAULT_MAX_CHARS,
    ): TtsSynthesisPlan {
        val fingerprint = fingerprint(document)
        var globalIndex = 0
        val chapters = document.chapters.map { chapter ->
            val chunks = TtsTextPipeline.chunks(
                text = chapter.text,
                language = document.language,
                targetChars = targetChars,
                maxChars = maxChars,
            ).map { chunk ->
                TtsPlannedChunk(
                    globalIndex = globalIndex++,
                    chapterIndex = chapter.index,
                    chapterChunkIndex = chunk.index,
                    chapterTitle = chapter.title,
                    text = chunk.text,
                    checkpointKey = "$fingerprint:${chapter.index}:${chunk.index}",
                )
            }
            TtsChapterPlan(
                chapterIndex = chapter.index,
                title = chapter.title,
                chunks = chunks,
            )
        }
        return TtsSynthesisPlan(
            documentFingerprint = fingerprint,
            language = document.language,
            chapters = chapters,
        )
    }

    fun fingerprint(document: BookDocument): String {
        val canonical = buildString {
            append("v=").append(PLAN_VERSION).append('\n')
            append("title=").append(document.title.orEmpty()).append('\n')
            append("authors=").append(document.authors.joinToString("|")).append('\n')
            append("lang=").append(document.language.orEmpty()).append('\n')
            append("series=").append(document.series.orEmpty()).append('\n')
            append("seriesNumber=").append(document.seriesNumber ?: "").append('\n')
            document.chapters.sortedBy { it.index }.forEach { chapter ->
                append("chapter=").append(chapter.index).append('|').append(chapter.title).append('\n')
                append(TtsTextPipeline.normalize(chapter.text, document.language)).append('\n')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
