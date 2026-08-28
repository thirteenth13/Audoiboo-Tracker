package org.audoiboo.tracker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.OutputStream

/** Optional user-selected library root that survives app reinstall when permission is persisted. */
internal object StorageAccess {
    private const val PREFS = "storage_access"
    private const val TREE = "tree_uri"

    fun treeUri(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(TREE, null)?.let(Uri::parse)

    fun setTree(context: Context, uri: Uri?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (uri == null) remove(TREE) else putString(TREE, uri.toString()) }
            .apply()
    }

    fun displayName(context: Context): String? = treeUri(context)?.let { DocumentFile.fromTreeUri(context, it)?.name ?: it.toString() }

    fun openOutput(context: Context, relativeDir: String, fileName: String, mime: String): Pair<Uri, OutputStream>? {
        val rootUri = treeUri(context) ?: return null
        var dir = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        relativeDir.replace('\\', '/').split('/').filter { it.isNotBlank() }.forEach { name ->
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name) ?: return null
        }
        dir.findFile(fileName)?.delete()
        val file = dir.createFile(mime, fileName) ?: return null
        val out = context.contentResolver.openOutputStream(file.uri) ?: return null
        return file.uri to out
    }

    fun clearDirectory(context: Context, relativeDir: String) {
        val rootUri = treeUri(context) ?: return
        var dir = DocumentFile.fromTreeUri(context, rootUri) ?: return
        val parts = relativeDir.replace('\\', '/').split('/').filter { it.isNotBlank() }
        for (name in parts) dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: return
        dir.listFiles().forEach { it.delete() }
    }
}
