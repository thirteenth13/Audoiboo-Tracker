package org.audoiboo.tracker.tts

import android.content.Context
import java.io.File
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.EbookImportException
import org.audoiboo.tracker.ebook.Fb2Importer
import org.audoiboo.tracker.plugin.flibusta.FlibustaDownloadResolver
import org.audoiboo.tracker.plugin.flibusta.FlibustaResolveResult

data class PreparedFlibustaBookTts(val document: BookDocument, val sourceUrl: String, val fileName: String, val tts: PreparedSherpaBookTts)
data class EnqueuedFlibustaBookTts(val session: TtsSession, val title: String, val relativeDir: String, val chunkCount: Int)

class FlibustaBookTtsFlow(
    private val resolveFb2: (String) -> FlibustaResolveResult,
    private val prepareTts: (BookDocument, Float, TtsQuality) -> Result<PreparedSherpaBookTts>,
) {
    fun prepare(bookUrl: String, speed: Float = 1.0f, quality: TtsQuality = TtsQuality.FAST): Result<PreparedFlibustaBookTts> = runCatching {
        require(bookUrl.isNotBlank()) { "Flibusta book URL must not be blank" }
        val resolved = when (val result = resolveFb2(bookUrl)) {
            is FlibustaResolveResult.Success -> result
            is FlibustaResolveResult.Failure -> error(buildString {
                append("Flibusta FB2 resolve failed: ").append(result.code)
                if (result.message.isNotBlank()) append(" — ").append(result.message)
                result.httpStatus?.let { append(" (HTTP ").append(it).append(')') }
            })
        }
        val imported = try { Fb2Importer.import(resolved.bytes) }
        catch (e: EbookImportException) { throw IllegalStateException("Downloaded Flibusta payload is not a valid FB2 book", e) }
        val prepared = prepareTts(imported.document, speed, quality).getOrThrow()
        PreparedFlibustaBookTts(imported.document, resolved.finalUrl, resolved.fileName, prepared)
    }

    fun enqueue(context: Context, bookUrl: String, outputDir: File, speed: Float = 1.0f, quality: TtsQuality = TtsQuality.FAST): Result<EnqueuedFlibustaBookTts> = runCatching {
        val prepared = prepare(bookUrl, speed, quality).getOrThrow()
        val sessionOutputDir = outputDirectory(outputDir, prepared.tts.session.sessionId)
        TtsGenerationScheduler.enqueue(context.applicationContext, prepared.tts.session, prepared.document, prepared.tts.model, sessionOutputDir)
        val title = prepared.document.title?.trim().takeUnless { it.isNullOrBlank() } ?: "Аудіокнига"
        EnqueuedFlibustaBookTts(prepared.tts.session, title, TtsPlayerLibraryBridge.relativePath(prepared.document), prepared.tts.chunkCount)
    }

    companion object {
        internal fun outputDirectory(root: File, sessionId: String): File = File(root, TtsStableId.hex(sessionId))
        fun create(context: Context): FlibustaBookTtsFlow {
            val resolver = FlibustaDownloadResolver()
            val coordinator = SherpaBookTtsCoordinator.create(context.applicationContext)
            return FlibustaBookTtsFlow(resolver::resolveFb2) { document, speed, quality -> coordinator.prepare(document, speed, quality) }
        }
    }
}
