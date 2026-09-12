package org.audoiboo.tracker.tts

import android.content.Context
import java.io.File
import org.audoiboo.tracker.ebook.BookDocument
import org.audoiboo.tracker.ebook.EbookImportException
import org.audoiboo.tracker.ebook.Fb2Importer
import org.audoiboo.tracker.plugin.flibusta.FlibustaDownloadResolver
import org.audoiboo.tracker.plugin.flibusta.FlibustaResolveResult

/** Fully resolved ebook plus the verified Sherpa inputs needed to schedule synthesis. */
data class PreparedFlibustaBookTts(
    val document: BookDocument,
    val sourceUrl: String,
    val fileName: String,
    val tts: PreparedSherpaBookTts,
)

/** Metadata the UI needs after a Flibusta TTS job has been handed to WorkManager. */
data class EnqueuedFlibustaBookTts(
    val session: TtsSession,
    val title: String,
    val relativeDir: String,
    val chunkCount: Int,
)

/**
 * Bridges the Flibusta FB2 pipeline to local Sherpa synthesis without putting TTS logic in a site parser.
 * Network/model work is synchronous and must be called from an IO coroutine or background thread.
 */
class FlibustaBookTtsFlow(
    private val resolveFb2: (String) -> FlibustaResolveResult,
    private val prepareTts: (BookDocument, Float) -> Result<PreparedSherpaBookTts>,
) {
    fun prepare(bookUrl: String, speed: Float = 1.0f): Result<PreparedFlibustaBookTts> = runCatching {
        require(bookUrl.isNotBlank()) { "Flibusta book URL must not be blank" }
        val resolved = when (val result = resolveFb2(bookUrl)) {
            is FlibustaResolveResult.Success -> result
            is FlibustaResolveResult.Failure -> error(
                buildString {
                    append("Flibusta FB2 resolve failed: ").append(result.code)
                    if (result.message.isNotBlank()) append(" — ").append(result.message)
                    result.httpStatus?.let { append(" (HTTP ").append(it).append(')') }
                }
            )
        }

        val imported = try {
            Fb2Importer.import(resolved.bytes)
        } catch (e: EbookImportException) {
            throw IllegalStateException("Downloaded Flibusta payload is not a valid FB2 book", e)
        }
        val prepared = prepareTts(imported.document, speed).getOrThrow()
        PreparedFlibustaBookTts(
            document = imported.document,
            sourceUrl = resolved.finalUrl,
            fileName = resolved.fileName,
            tts = prepared,
        )
    }

    fun enqueue(
        context: Context,
        bookUrl: String,
        outputDir: File,
        speed: Float = 1.0f,
    ): Result<EnqueuedFlibustaBookTts> = runCatching {
        val prepared = prepare(bookUrl, speed).getOrThrow()
        val sessionOutputDir = outputDirectory(outputDir, prepared.tts.session.sessionId)
        TtsGenerationScheduler.enqueue(
            context = context.applicationContext,
            session = prepared.tts.session,
            document = prepared.document,
            model = prepared.tts.model,
            outputDir = sessionOutputDir,
        )
        val title = prepared.document.title?.trim().takeUnless { it.isNullOrBlank() } ?: "Аудіокнига"
        EnqueuedFlibustaBookTts(
            session = prepared.tts.session,
            title = title,
            relativeDir = TtsPlayerLibraryBridge.relativePath(prepared.document),
            chunkCount = prepared.tts.chunkCount,
        )
    }

    companion object {
        internal fun outputDirectory(root: File, sessionId: String): File =
            File(root, TtsStableId.hex(sessionId))

        fun create(context: Context): FlibustaBookTtsFlow {
            val resolver = FlibustaDownloadResolver()
            val coordinator = SherpaBookTtsCoordinator.create(context.applicationContext)
            return FlibustaBookTtsFlow(
                resolveFb2 = resolver::resolveFb2,
                prepareTts = coordinator::prepare,
            )
        }
    }
}
