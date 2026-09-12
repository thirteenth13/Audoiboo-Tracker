package org.audoiboo.tracker.tts

import java.io.File
import org.audoiboo.tracker.ebook.TtsChapterPlan
import org.audoiboo.tracker.ebook.TtsSynthesisPlan

class TtsChapterGenerator(
    private val provider: TtsProvider,
    private val workRoot: File,
) {
    suspend fun generate(
        plan: TtsSynthesisPlan,
        initialSession: TtsSession,
        outputDir: File,
        onCheckpoint: (TtsSession) -> Unit = {},
    ): TtsSession {
        require(initialSession.providerId == provider.id) { "TTS provider mismatch" }
        require(initialSession.documentFingerprint == plan.documentFingerprint) { "TTS document fingerprint mismatch" }
        require(initialSession.voice.stableKey.isNotBlank())
        val language = plan.language ?: initialSession.voice.language
        require(provider.supportsLanguage(language)) { "Unsupported TTS language: $language" }

        outputDir.mkdirs()
        val sessionWork = File(workRoot, safe(initialSession.sessionId)).apply { mkdirs() }
        var session = initialSession.copy(state = TtsSessionState.RUNNING, lastError = null)
        onCheckpoint(session)

        return try {
            for (chapter in plan.chapters) {
                if (chapter.chapterIndex in session.completedChapterIndexes) continue
                val chapterWork = File(sessionWork, "chapter-${chapter.chapterIndex}").apply { mkdirs() }

                for (chunk in chapter.chunks) {
                    if (chunk.globalIndex < session.nextGlobalChunkIndex) continue
                    val chunkFile = File(chapterWork, "chunk-%06d.wav".format(chunk.chapterChunkIndex))
                    val tempFile = File(chapterWork, chunkFile.name + ".tmp")
                    tempFile.delete()
                    provider.synthesize(
                        TtsSynthesisRequest(
                            text = chunk.text,
                            language = language,
                            voice = session.voice,
                            speed = session.speed,
                            outputPath = tempFile.absolutePath,
                        )
                    )
                    require(tempFile.isFile && tempFile.length() > 44L) { "TTS provider produced an invalid WAV chunk" }
                    if (chunkFile.exists()) chunkFile.delete()
                    require(tempFile.renameTo(chunkFile)) { "Cannot commit synthesized chunk" }
                    session = session.advance(chunk.globalIndex + 1)
                    onCheckpoint(session)
                }

                assembleChapter(chapter, chapterWork, outputDir)
                session = session.advance(session.nextGlobalChunkIndex, chapter.chapterIndex)
                onCheckpoint(session)
                chapterWork.deleteRecursively()
            }
            val completed = session.complete()
            onCheckpoint(completed)
            sessionWork.deleteRecursively()
            completed
        } catch (t: Throwable) {
            session.fail(t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName).also(onCheckpoint)
        }
    }

    private fun assembleChapter(chapter: TtsChapterPlan, chapterWork: File, outputDir: File) {
        if (chapter.chunks.isEmpty()) return
        val output = File(outputDir, "chapter-%04d.wav".format(chapter.chapterIndex))
        val tempOutput = File(outputDir, output.name + ".tmp")
        tempOutput.delete()
        for (chunk in chapter.chunks) {
            val chunkFile = File(chapterWork, "chunk-%06d.wav".format(chunk.chapterChunkIndex))
            require(chunkFile.isFile) { "Missing synthesized chunk ${chunk.globalIndex}" }
            Pcm16Wav.append(chunkFile, tempOutput)
        }
        require(tempOutput.isFile && tempOutput.length() > 44L) { "Generated chapter WAV is empty" }
        if (output.exists()) output.delete()
        require(tempOutput.renameTo(output)) { "Cannot commit generated chapter" }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
