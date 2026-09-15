package org.audoiboo.tracker.tts

import java.io.File
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.TtsSynthesisPlanner

data class TtsGeneratedChapter(
    val chapterIndex: Int,
    val title: String,
    val audioFile: File,
)

data class TtsBookGenerationResult(
    val session: TtsSession,
    val chapters: List<TtsGeneratedChapter>,
)

class TtsBookGenerator(
    private val chapterGenerator: TtsChapterGenerator,
) {
    suspend fun generate(
        document: BookDocument,
        initialSession: TtsSession,
        outputDir: File,
        onCheckpoint: (TtsSession) -> Unit = {},
    ): TtsBookGenerationResult {
        val plan = TtsSynthesisPlanner.build(document)
        require(initialSession.documentFingerprint == plan.documentFingerprint) {
            "TTS document fingerprint mismatch"
        }
        val finalSession = chapterGenerator.generate(
            plan = plan,
            initialSession = initialSession,
            outputDir = outputDir,
            onCheckpoint = onCheckpoint,
        )
        val chapters = plan.chapters.mapNotNull { chapter ->
            val file = File(outputDir, "chapter-%04d.wav".format(chapter.chapterIndex))
            if (!file.isFile || chapter.chapterIndex !in finalSession.completedChapterIndexes) return@mapNotNull null
            TtsGeneratedChapter(
                chapterIndex = chapter.chapterIndex,
                title = chapter.title,
                audioFile = file,
            )
        }
        return TtsBookGenerationResult(finalSession, chapters)
    }
}
