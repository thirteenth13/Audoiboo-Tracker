package org.audoiboo.tracker.tts

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/** Persistent, user-visible storage for downloaded local TTS models. */
internal object TtsModelStorage {
    const val APP_DIRECTORY = "Audoiboo"
    const val MODELS_DIRECTORY = "models"

    fun root(context: Context): File {
        requireSharedStorageAccess(context)
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return root(downloads)
    }

    internal fun root(downloadsDirectory: File): File =
        File(File(downloadsDirectory, APP_DIRECTORY), MODELS_DIRECTORY)

    /**
     * Sherpa-ONNX requires normal filesystem paths for its model files. On Android 11+ that means
     * the sideloaded app needs All files access to keep models in Download/Audoiboo/models rather
     * than private app storage. The settings screen is opened on first use; retrying TTS after the
     * grant reuses the same public model directory across app reinstalls.
     */
    private fun requireSharedStorageAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) return

        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
        error("Надайте Audoiboo доступ до всіх файлів і повторіть запуск локального TTS")
    }
}
