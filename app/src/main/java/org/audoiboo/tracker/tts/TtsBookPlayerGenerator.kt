package org.audoiboo.tracker.tts

import android.content.Context
import java.io.File
import org.audoiboo.tracker.ebook.BookDocument

/** Runs book synthesis, persists checkpoints, and publishes a completed result to the player library. */
internal class TtsBookPlayerGenerator private constructor(
    private val bookGenerator: TtsBookGenerator,
    private val publish: (BookDocument, TtsBookGenerationResult) -> Unit,
    private val sessionStore: TtsSessionStore?,
) {
    constructor(context: Context, bookGenerator: TtsBookGenerator) : this(
        bookGenerator = bookGenerator,
        publish = { document, result -> TtsPlayerLibraryBridge.register(context, document, result) },
        sessionStore = TtsSessionStore(File(context.filesDir, "tts/sessions")),
    )

    internal constructor(
        bookGenerator: TtsBookGenerator,
        publishForTest: (BookDocument, TtsBookGenerationResult) -> Unit,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
        sessionStoreForTest: TtsSessionStore? = null,
    ) : this(bookGenerator, publishForTest, sessionStoreForTest)

    suspend fun generate(
        document: BookDocument,
        initialSession: TtsSession,
        outputDir: File,
        onCheckpoint: (TtsSession) -> Unit = {},
    ): TtsBookGenerationResult {
        val startingSession = sessionStore
            ?.load(initialSession.sessionId)
            ?.takeIf { persisted -> canResume(persisted, initialSession) }
            ?: initialSession

        val checkpoint: (TtsSession) -> Unit = { session ->
            sessionStore?.save(session)
            onCheckpoint(session)
        }

        val result = bookGenerator.generate(
            document = document,
            initialSession = startingSession,
            outputDir = outputDir,
            onCheckpoint = checkpoint,
        )
        if (result.session.state == TtsSessionState.COMPLETED) {
            publish(document, result)
            sessionStore?.delete(result.session.sessionId)
        }
        return result
    }

    private fun canResume(persisted: TtsSession, requested: TtsSession): Boolean =
        persisted.state != TtsSessionState.COMPLETED &&
            persisted.sessionId == requested.sessionId &&
            persisted.providerId == requested.providerId &&
            persisted.documentFingerprint == requested.documentFingerprint &&
            persisted.voice.stableKey == requested.voice.stableKey &&
            persisted.speed == requested.speed
}
