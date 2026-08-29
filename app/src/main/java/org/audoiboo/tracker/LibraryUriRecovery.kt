package org.audoiboo.tracker

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class LibraryUriRecoveryResult(
    val rebound: Int,
    val readable: Int,
    val unresolved: Int
)

/** Rebinds stale library URIs without discarding library metadata or playback state. */
internal object LibraryUriRecovery {
    suspend fun recover(context: Context): LibraryUriRecoveryResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val current = PlayerLibrary.all(app)
        if (current.isEmpty()) return@withContext LibraryUriRecoveryResult(0, 0, 0)

        var rebound = 0
        var readable = 0
        var unresolved = 0
        val replacements = current.toMutableList()

        current.forEachIndexed { index, item ->
            val old = runCatching { Uri.parse(item.uri) }.getOrNull()
            if (old != null && canRead(app, old)) {
                readable++
                return@forEachIndexed
            }

            val replacement = findInSaf(app, item) ?: findInMediaStore(app, item)
            if (replacement == null || !canRead(app, replacement)) {
                unresolved++
                return@forEachIndexed
            }

            val moved = runCatching {
                PlayerUriMigration.remap(app, item.uri, replacement.toString())
            }.isSuccess
            if (!moved) {
                unresolved++
                return@forEachIndexed
            }

            replacements[index] = item.copy(uri = replacement.toString())
            rebound++
        }

        if (rebound > 0) PlayerLibrary.replaceAll(app, replacements)
        LibraryUriRecoveryResult(rebound, readable, unresolved)
    }

    private fun findInSaf(context: Context, item: PlayerLibraryItem): Uri? {
        val relative = LibraryUriRecoveryPolicy.safRelativeDir(item.relativePath) ?: return null
        return StorageAccess.existingFile(context, relative, item.name)?.uri
    }

    private fun findInMediaStore(context: Context, item: PlayerLibraryItem): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val candidates = LibraryUriRecoveryPolicy.mediaStoreRelativeDirs(item.relativePath)
        if (candidates.isEmpty()) return null
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.RELATIVE_PATH)
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME}=?"
        val args = arrayOf(item.name)
        return runCatching {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
                while (cursor.moveToNext()) {
                    val relative = cursor.getString(pathColumn).orEmpty().trim('/')
                    if (candidates.any { it.equals(relative, ignoreCase = true) }) {
                        return@use ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun canRead(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
}
