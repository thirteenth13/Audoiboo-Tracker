package org.audoiboo.tracker.tts

import java.io.File
import kotlinx.coroutines.CancellationException
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
            require(plan.chapters.any { it.chunks.isNotEmpty() }) { "TTS plan contains no synthesizable text" }

            for (chapter in plan.chapters) {
                if (chapter.chunks.isEmpty()) continue
                if (chapter.chapterIndex in session.completedChapterIndexes) continue
                val chapterWork = File(sessionWork, "chapter-${chapter.chapterIndex}").apply { mkdirs() }

                for (chunk in chapter.chunks) {
                    if (chunk.globalIndex < session.nextGlobalChunkIndex) continue
                    val chunkFile = File(chapterWork, "chunk-%06d.wav".format(chunk.chapterChunkIndex))
                    val tempFile = File(chapterWork, chunkFile.name + ".tmp")
                    tempFile.delete()
                    try {
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
                        commitTempFile(tempFile, chunkFile, "Cannot commit synthesized chunk")
                    } catch (t: Throwable) {
                        tempFile.delete()
                        throw t
                    }
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            session.fail(t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName).also(onCheckpoint)
        }
    }

    private fun assembleChapter(chapter: TtsChapterPlan, chapterWork: File, outputDir: File) {
        val output = File(outputDir, "chapter-%04d.wav".format(chapter.chapterIndex))
        val tempOutput = File(outputDir, output.name + ".tmp")
        tempOutput.delete()
        try {
            for (chunk in chapter.chunks) {
                val chunkFile = File(chapterWork, "chunk-%06d.wav".format(chunk.chapterChunkIndex))
                require(chunkFile.isFile) { "Missing synthesized chunk ${chunk.globalIndex}" }
                Pcm16Wav.append(chunkFile, tempOutput)
            }
            require(tempOutput.isFile && tempOutput.length() > 44L) { "Generated chapter WAV is empty" }
            commitTempFile(tempOutput, output, "Cannot commit generated chapter")
        } catch (t: Throwable) {
            tempOutput.delete()
            throw t
        }
    }

    private fun commitTempFile(temp: File, target: File, errorMessage: String) {
        if (temp.renameTo(target)) return
        if (!target.exists()) error(errorMessage)

        val backup = File(target.parentFile, target.name + ".bak")
        backup.delete()
        require(target.renameTo(backup)) { errorMessage }
        try {
            require(temp.renameTo(target)) { errorMessage }
            backup.delete()
        } catch (t: Throwable) {
            target.delete()
            backup.renameTo(target)
            throw t
        }
    }

    private fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
