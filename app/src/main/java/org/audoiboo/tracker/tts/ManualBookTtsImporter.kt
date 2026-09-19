package org.audoiboo.tracker.tts

import android.content.Context
import android.net.Uri
import java.io.File
import org.audoiboo.tracker.ebook.Fb2Importer

data class EnqueuedManualBookTts(val session: TtsSession, val title: String, val chunkCount: Int)

object ManualBookTtsImporter {
    fun enqueue(context: Context, uri: Uri, speed: Float = 1.0f, quality: TtsQuality = TtsQuality.FAST): Result<EnqueuedManualBookTts> = runCatching {
        val payload = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не вдалося відкрити файл")
        val imported = Fb2Importer.import(payload)
        val prepared = SherpaBookTtsCoordinator.create(context.applicationContext).prepare(imported.document, speed, quality).getOrThrow()
        val outputDir = File(context.filesDir, "tts/manual/" + org.audoiboo.tracker.tts.TtsStableId.hex(prepared.session.sessionId))
        TtsGenerationScheduler.enqueue(context.applicationContext, prepared.session, imported.document, prepared.model, outputDir)
        EnqueuedManualBookTts(prepared.session, imported.document.title?.trim().takeUnless { it.isNullOrBlank() } ?: "Аудіокнига", prepared.chunkCount)
    }
}
