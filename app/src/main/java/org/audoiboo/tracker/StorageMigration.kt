package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class StorageMigrationResult(
    val migrated: Int,
    val skipped: Int,
    val failed: Int
)

/**
 * Copies indexed library files into the selected SAF tree and updates PlayerLibrary URIs only for
 * successful copies. Sources are deliberately left untouched so migration cannot destroy the only
 * readable copy if Android revokes a permission or a provider fails midway.
 */
internal object StorageMigration {
    suspend fun copyIndexedLibrary(context: Context): StorageMigrationResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (StorageAccess.treeUri(app) == null) return@withContext StorageMigrationResult(0, 0, 0)
        val current = PlayerLibrary.all(app)
        if (current.isEmpty()) return@withContext StorageMigrationResult(0, 0, 0)

        var migrated = 0
        var skipped = 0
        var failed = 0
        val replacements = current.toMutableList()

        current.forEachIndexed { index, item ->
            val source = runCatching { Uri.parse(item.uri) }.getOrNull()
            if (source == null || item.uri.isBlank()) {
                failed++
                return@forEachIndexed
            }
            if (source.scheme == "content" && source.authority == "com.android.externalstorage.documents") {
                skipped++
                return@forEachIndexed
            }
            val relativeDir = normalizeDestination(item.relativePath)
            val mime = mimeFor(item.name)
            val copied = runCatching {
                val input = app.contentResolver.openInputStream(source) ?: return@runCatching null
                val target = StorageAccess.openOutput(app, relativeDir, item.name, mime) ?: run {
                    input.close()
                    return@runCatching null
                }
                input.use { src -> target.second.use { dst -> src.copyTo(dst, 128 * 1024) } }
                target.first
            }.getOrNull()
            if (copied == null) {
                failed++
            } else {
                replacements[index] = item.copy(uri = copied.toString(), relativePath = relativeDir)
                migrated++
            }
        }

        if (migrated > 0) PlayerLibrary.replaceAll(app, replacements)
        StorageMigrationResult(migrated, skipped, failed)
    }

    private fun normalizeDestination(path: String): String {
        val normalized = path.replace('\\', '/').trim('/')
        return normalized.removePrefix("Download/").removePrefix("download/")
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        else -> "application/octet-stream"
    }
}
