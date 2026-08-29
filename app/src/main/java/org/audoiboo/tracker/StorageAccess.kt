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
    private const val RUNTIME_PREFS = "storage_access_runtime"
    private const val LAST_VALID_TREE = "last_valid_tree_uri"

    fun treeUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runtime = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val restoredRaw = prefs.getString(TREE, null)
        val fallbackRaw = runtime.getString(LAST_VALID_TREE, null)
        val restored = restoredRaw?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val fallback = fallbackRaw?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val restoredAllowed = restored != null && hasPersistedPermission(context, restored)
        val fallbackAllowed = fallback != null && hasPersistedPermission(context, fallback)
        val selectedRaw = StorageTreePolicy.select(restoredRaw, restoredAllowed, fallbackRaw, fallbackAllowed)
        if (selectedRaw == null) {
            if (!restoredAllowed && restoredRaw != null) prefs.edit().remove(TREE).apply()
            if (!fallbackAllowed && fallbackRaw != null) runtime.edit().remove(LAST_VALID_TREE).apply()
            return null
        }
        val selected = runCatching { Uri.parse(selectedRaw) }.getOrNull() ?: return null
        if (selectedRaw != restoredRaw) prefs.edit().putString(TREE, selectedRaw).apply()
        runtime.edit().putString(LAST_VALID_TREE, selectedRaw).apply()
        return selected
    }

    fun setTree(context: Context, uri: Uri?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val runtime = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(TREE, null)?.let { runCatching { Uri.parse(it) }.getOrNull() }
        if (uri == null) {
            prefs.edit().remove(TREE).apply()
            runtime.edit().remove(LAST_VALID_TREE).apply()
            old?.let { releasePermission(context, it) }
            return
        }
        require(hasPersistedPermission(context, uri)) { "Storage tree permission is not persisted" }
        prefs.edit().putString(TREE, uri.toString()).apply()
        runtime.edit().putString(LAST_VALID_TREE, uri.toString()).apply()
        if (old != null && old != uri) releasePermission(context, old)
    }

    fun persistTreePermission(context: Context, uri: Uri, flags: Int): Boolean {
        val wanted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (wanted == 0) return false
        return runCatching {
            context.contentResolver.takePersistableUriPermission(uri, wanted)
            hasPersistedPermission(context, uri)
        }.getOrDefault(false)
    }

    fun displayName(context: Context): String? = treeUri(context)?.let {
        DocumentFile.fromTreeUri(context, it)?.name ?: it.toString()
    }

    fun existingFile(context: Context, relativeDir: String, fileName: String): DocumentFile? {
        if (!StoragePathPolicy.validFileName(fileName)) return null
        val safeDir = StoragePathPolicy.normalizeRelativeDir(relativeDir) ?: return null
        var dir = root(context) ?: return null
        for (name in safeDir.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: return null
        }
        return dir.findFile(fileName)?.takeIf { it.isFile }
    }

    fun openOutput(context: Context, relativeDir: String, fileName: String, mime: String): Pair<Uri, OutputStream>? {
        if (!StoragePathPolicy.validFileName(fileName)) return null
        val safeDir = StoragePathPolicy.normalizeRelativeDir(relativeDir) ?: return null
        var dir = root(context) ?: return null
        for (name in safeDir.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: dir.createDirectory(name) ?: return null
        }
        dir.findFile(fileName)?.delete()
        val file = dir.createFile(mime, fileName) ?: return null
        val out = context.contentResolver.openOutputStream(file.uri) ?: return null
        return file.uri to out
    }

    fun clearDirectory(context: Context, relativeDir: String) {
        val safeDir = StoragePathPolicy.normalizeRelativeDir(relativeDir) ?: return
        var dir = root(context) ?: return
        for (name in safeDir.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(name)?.takeIf { it.isDirectory } ?: return
        }
        dir.listFiles().forEach { it.delete() }
    }

    private fun root(context: Context): DocumentFile? {
        val uri = treeUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)?.takeIf { it.isDirectory && it.canWrite() }
    }

    private fun hasPersistedPermission(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { p ->
            p.uri == uri && p.isReadPermission && p.isWritePermission
        }

    private fun releasePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
