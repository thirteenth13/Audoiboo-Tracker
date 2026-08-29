package org.audoiboo.tracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/** Optional user-selected library root backed by a persisted SAF permission. */
internal object StorageAccess {
    private const val PREFS = "storage_access"
    private const val TREE = "tree_uri"

    fun treeUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, null) ?: return null
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        return uri.takeIf { hasPersistedPermission(context, it) }
    }

    fun hasPersistedPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission && permission.isWritePermission
        }

    fun persistTree(context: Context, uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val granted = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            hasPersistedPermission(context, uri)
        }.getOrDefault(false)
        if (granted) setTree(context, uri)
        return granted
    }

    fun setTree(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (uri == null) remove(TREE) else putString(TREE, uri.toString()) }
            .apply()
    }

    fun clearTree(context: Context) {
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
        }
        setTree(context, null)
    }

    fun displayName(context: Context): String? = treeUri(context)?.let { DocumentFile.fromTreeUri(context, it)?.name ?: it.toString() }

    fun openOutput(context: Context, relativeDir: String, fileName: String, mime: String): Pair<Uri, OutputStream>? {
        val rootUri = treeUri(context) ?: return null
        val safeDir = StoragePathPolicy.normalizeRelativeDir(relativeDir) ?: return null
        if (!StoragePathPolicy.validFileName(fileName)) return null
        var dir = DocumentFile.fromTreeUri(context, rootUri)?.takeIf { it.exists() && it.isDirectory && it.canWrite() } ?: return null
        safeDir.split('/').filter { it.isNotBlank() }.forEach { name ->
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name) ?: return null
        }
        dir.findFile(fileName)?.let { existing -> if (!existing.delete()) return null }
        val file = dir.createFile(mime, fileName) ?: return null
        val out = context.contentResolver.openOutputStream(file.uri) ?: return null
        return file.uri to out
    }

    fun clearDirectory(context: Context, relativeDir: String) {
        val rootUri = treeUri(context) ?: return
        val safeDir = StoragePathPolicy.normalizeRelativeDir(relativeDir) ?: return
        var dir = DocumentFile.fromTreeUri(context, rootUri)?.takeIf { it.exists() && it.isDirectory } ?: return
        val parts = safeDir.split('/').filter { it.isNotBlank() }
        for (name in parts) dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: return
        dir.listFiles().forEach { it.delete() }
    }
}
