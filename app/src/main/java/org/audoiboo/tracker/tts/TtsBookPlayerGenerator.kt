package org.audoiboo.tracker.tts

import android.content.Context
import java.io.File
import org.audoiboo.tracker.ebook.BookDocument

/** Runs book synthesis and publishes a fully completed result to the existing player library. */
internal class TtsBookPlayerGenerator private constructor(
    private val bookGenerator: TtsBookGenerator,
    private val publish: (BookDocument, TtsBookGenerationResult) -> Unit,
) {
    constructor(context: Context, bookGenerator: TtsBookGenerator) : this(
        bookGenerator = bookGenerator,
        publish = { document, result -> TtsPlayerLibraryBridge.register(context, document, result) },
    )

    internal constructor(
        bookGenerator: TtsBookGenerator,
        publishForTest: (BookDocument, TtsBookGenerationResult) -> Unit,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(bookGenerator, publishForTest)

    suspend fun generate(
        document: BookDocument,
        initialSession: TtsSession,
        outputDir: File,
        onCheckpoint: (TtsSession) -> Unit = {},
    ): TtsBookGenerationResult {
        val result = bookGenerator.generate(
            document = document,
            initialSession = initialSession,
            outputDir = outputDir,
            onCheckpoint = onCheckpoint,
        )
        if (result.session.state == TtsSessionState.COMPLETED) {
            publish(document, result)
        }
        return result
    }
}
