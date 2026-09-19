package org.audoiboo.tracker.tts

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import org.audoiboo.tracker.ebook.Fb2Importer

data class EnqueuedManualBookTts(val session: TtsSession, val title: String, val chunkCount: Int)

object ManualBookTtsImporter {
    private const val TAG = "AudoibooManualTts"

    fun enqueue(context: Context, uri: Uri, speed: Float = 1.0f, quality: TtsQuality = TtsQuality.FAST): Result<EnqueuedManualBookTts> = runCatching {
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val mime = context.contentResolver.getType(uri)
        Log.i(TAG, "IMPORT START name=${name ?: "?"} mime=${mime ?: "?"} uri=$uri")

        val payload = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Не вдалося відкрити файл")
        } catch (t: Throwable) {
            Log.e(TAG, "IMPORT FAIL stage=read name=${name ?: "?"}", t)
            throw t
        }
        Log.i(TAG, "IMPORT READ name=${name ?: "?"} bytes=${payload.size} signature=${payload.take(4).joinToString("") { "%02x".format(it) }}")

        val imported = try {
            Fb2Importer.import(payload)
        } catch (t: Throwable) {
            Log.e(TAG, "IMPORT FAIL stage=fb2-parse name=${name ?: "?"} bytes=${payload.size}", t)
            throw IllegalStateException("FB2 parse: ${t.javaClass.simpleName}: ${t.message ?: "без повідомлення"}", t)
        }
        Log.i(TAG, "IMPORT PARSED title='${imported.document.title}' chapters=${imported.document.chapters.size} language=${imported.document.language}")

        val prepared = try {
            SherpaBookTtsCoordinator.create(context.applicationContext).prepare(imported.document, speed, quality).getOrThrow()
        } catch (t: Throwable) {
            Log.e(TAG, "IMPORT FAIL stage=tts-prepare title='${imported.document.title}'", t)
            throw IllegalStateException("TTS prepare: ${t.javaClass.simpleName}: ${t.message ?: "без повідомлення"}", t)
        }
        Log.i(TAG, "IMPORT TTS READY session=${prepared.session.sessionId} chunks=${prepared.chunkCount}")

        val outputDir = File(context.filesDir, "tts/manual/" + TtsStableId.hex(prepared.session.sessionId))
        try {
            TtsGenerationScheduler.enqueue(context.applicationContext, prepared.session, imported.document, prepared.model, outputDir)
        } catch (t: Throwable) {
            Log.e(TAG, "IMPORT FAIL stage=enqueue session=${prepared.session.sessionId}", t)
            throw IllegalStateException("TTS enqueue: ${t.javaClass.simpleName}: ${t.message ?: "без повідомлення"}", t)
        }
        Log.i(TAG, "IMPORT ENQUEUED session=${prepared.session.sessionId} output=${outputDir.absolutePath}")
        EnqueuedManualBookTts(prepared.session, imported.document.title?.trim().takeUnless { it.isNullOrBlank() } ?: "Аудіокнига", prepared.chunkCount)
    }.onFailure { Log.e(TAG, "IMPORT END failure=${it.javaClass.name}: ${it.message}", it) }
}
