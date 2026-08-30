package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Safety limits for a single .abplugin archive. */
data class PluginArchiveLimits(
    val maxEntries: Int = 128,
    val maxCompressedBytes: Long = 8L * 1024L * 1024L,
    val maxUncompressedBytes: Long = 32L * 1024L * 1024L,
    val maxEntryBytes: Long = 8L * 1024L * 1024L,
    val maxManifestBytes: Long = 128L * 1024L
)

sealed interface PluginInstallResult {
    data class Installed(
        val registration: SourcePluginRegistration,
        val installDir: File,
        val previousVersion: Int?
    ) : PluginInstallResult

    data class Rejected(val reason: String) : PluginInstallResult
    data class Failed(val reason: String, val cause: Throwable? = null) : PluginInstallResult
}

fun interface PluginManifestDecoder {
    fun decode(json: String): PluginPackageManifest
}

/**
 * JSON codec is deliberately kept at the package boundary. External plugins never receive Android
 * objects or Kotlin implementation classes; their stable ABI is the manifest + source DTO contract.
 */
object JsonPluginManifestDecoder : PluginManifestDecoder {
    override fun decode(json: String): PluginPackageManifest {
        val root = JSONObject(json)
        val permissionsJson = root.optJSONObject("permissions")
        val permissions = PluginPermissions(
            networkHosts = permissionsJson?.optJSONArray("networkHosts").toStringSet(),
            downloadHosts = permissionsJson?.optJSONArray("downloadHosts").toStringSet(),
            cookies = permissionsJson?.optBoolean("cookies", false) ?: false,
            javascript = permissionsJson?.optBoolean("javascript", false) ?: false
        )
        val capabilities = root.optJSONArray("capabilities").toStringSet().mapTo(linkedSetOf()) { value ->
            SourceCapability.valueOf(value)
        }
        val entrypointsJson = root.optJSONObject("entrypoints")
        val entrypoints = linkedMapOf<String, String>()
        if (entrypointsJson != null) {
            val keys = entrypointsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                entrypoints[key] = entrypointsJson.getString(key)
            }
        }
        return PluginPackageManifest(
            id = root.getString("id"),
            name = root.getString("name"),
            version = root.getInt("version"),
            apiVersion = root.getInt("apiVersion"),
            runtime = root.optString("runtime", PluginRuntime.DECLARATIVE.name).let(PluginRuntime::valueOf),
            hosts = root.getJSONArray("hosts").toStringSet(),
            capabilities = capabilities,
            permissions = permissions,
            entrypoints = entrypoints
        )
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapTo(linkedSetOf()) { index -> getString(index) }
    }
}

/**
 * Validates and installs .abplugin ZIP packages into app-owned storage.
 *
 * Package code is never executed here. A successful external package remains DISABLED until a
 * sandbox runtime is introduced. Installation is staged and the active-version pointer is only
 * swapped after every archive entry has been validated and extracted.
 */
class PluginPackageInstaller(
    private val pluginRoot: File,
    private val manager: SourcePluginManager,
    private val manifestDecoder: PluginManifestDecoder = JsonPluginManifestDecoder,
    private val limits: PluginArchiveLimits = PluginArchiveLimits()
) {
    fun install(packageFile: File): PluginInstallResult {
        if (!packageFile.isFile) return PluginInstallResult.Rejected("Plugin package does not exist")
        if (!PluginPackagePolicy.isPluginPackageName(packageFile.name)) {
            return PluginInstallResult.Rejected("Expected .$PLUGIN_PACKAGE_EXTENSION package")
        }
        if (packageFile.length() <= 0L || packageFile.length() > limits.maxCompressedBytes) {
            return PluginInstallResult.Rejected("Plugin package compressed size is outside allowed limits")
        }

        val staging = File(pluginRoot, ".staging/${UUID.randomUUID()}")
        return try {
            pluginRoot.mkdirs()
            staging.mkdirs()
            ZipFile(packageFile).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val archiveError = validateArchive(entries)
                if (archiveError != null) return PluginInstallResult.Rejected(archiveError)

                val manifestEntry = entries.singleOrNull { !it.isDirectory && it.name == PLUGIN_MANIFEST_FILE }
                    ?: return PluginInstallResult.Rejected("Package must contain exactly one $PLUGIN_MANIFEST_FILE at archive root")
                val manifestJson = readBounded(zip, manifestEntry, limits.maxManifestBytes)
                    ?: return PluginInstallResult.Rejected("Plugin manifest exceeds size limit")
                val manifest = runCatching { manifestDecoder.decode(manifestJson) }
                    .getOrElse { return PluginInstallResult.Rejected("Invalid plugin manifest: ${it.message ?: "parse error"}") }
                val validation = PluginPackagePolicy.validate(manifest)
                if (!validation.valid) {
                    return PluginInstallResult.Rejected(validation.errors.joinToString("; "))
                }

                val currentVersion = activeVersion(manifest.id)
                if (currentVersion != null && manifest.version < currentVersion) {
                    return PluginInstallResult.Rejected("Refusing plugin downgrade from $currentVersion to ${manifest.version}")
                }

                extractValidated(zip, entries, staging)
                val contentsValidation = PluginPackageContentsPolicy.validate(manifest, staging)
                if (!contentsValidation.valid) {
                    return PluginInstallResult.Rejected(contentsValidation.errors.joinToString("; "))
                }
                val versionDir = File(pluginRoot, "installed/${manifest.id}/versions/${manifest.version}")
                versionDir.parentFile?.mkdirs()
                if (versionDir.exists() && !versionDir.deleteRecursively()) {
                    throw IOException("Unable to replace existing plugin version directory")
                }
                if (!staging.renameTo(versionDir)) {
                    copyDirectory(staging, versionDir)
                    staging.deleteRecursively()
                }

                val registration = manager.registerPackageManifest(manifest, versionDir.absolutePath)
                if (registration.state == PluginState.QUARANTINED || registration.state == PluginState.INCOMPATIBLE) {
                    versionDir.deleteRecursively()
                    return PluginInstallResult.Rejected(registration.failureReason ?: "Plugin cannot be installed")
                }

                writeActiveVersionAtomically(manifest.id, manifest.version)
                pruneOldVersions(manifest.id, keep = setOf(manifest.version, currentVersion).filterNotNull().toSet())
                PluginInstallResult.Installed(registration, versionDir, currentVersion)
            }
        } catch (t: Throwable) {
            PluginInstallResult.Failed(t.message ?: "Plugin installation failed", t)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun rollback(pluginId: String): Boolean {
        val versionsDir = File(pluginRoot, "installed/$pluginId/versions")
        val active = activeVersion(pluginId) ?: return false
        val previous = versionsDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { it.name.toIntOrNull() }
            .filter { it < active }
            .maxOrNull() ?: return false
        writeActiveVersionAtomically(pluginId, previous)
        return true
    }

    fun activeVersion(pluginId: String): Int? =
        File(pluginRoot, "installed/$pluginId/active-version").takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toIntOrNull()

    private fun validateArchive(entries: List<ZipEntry>): String? {
        if (entries.isEmpty()) return "Plugin archive is empty"
        if (entries.size > limits.maxEntries) return "Plugin archive contains too many entries"
        val seen = hashSetOf<String>()
        var declaredTotal = 0L
        for (entry in entries) {
            val normalized = entry.name.replace('\\', '/').trimEnd('/')
            if (normalized.isBlank()) return "Plugin archive contains an empty path"
            if (!PluginPackagePolicy.isSafeRelativePath(normalized)) return "Unsafe archive path: ${entry.name}"
            if (!seen.add(normalized)) return "Duplicate archive path: $normalized"
            if (!entry.isDirectory) {
                if (entry.size > limits.maxEntryBytes) return "Archive entry exceeds size limit: $normalized"
                if (entry.size >= 0) {
                    declaredTotal += entry.size
                    if (declaredTotal > limits.maxUncompressedBytes) return "Plugin archive exceeds uncompressed size limit"
                }
            }
        }
        return null
    }

    private fun extractValidated(zip: ZipFile, entries: List<ZipEntry>, staging: File) {
        val rootPath = staging.canonicalFile.toPath()
        var total = 0L
        entries.forEach { entry ->
            val relative = entry.name.replace('\\', '/').trimEnd('/')
            val target = File(staging, relative).canonicalFile
            if (!target.toPath().startsWith(rootPath)) throw IOException("Zip-slip path rejected: ${entry.name}")
            if (entry.isDirectory) {
                target.mkdirs()
                return@forEach
            }
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        entryBytes += read
                        total += read
                        if (entryBytes > limits.maxEntryBytes) throw IOException("Archive entry exceeds size limit: ${entry.name}")
                        if (total > limits.maxUncompressedBytes) throw IOException("Plugin archive exceeds uncompressed size limit")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    private fun readBounded(zip: ZipFile, entry: ZipEntry, maxBytes: Long): String? {
        zip.getInputStream(entry).use { input ->
            val bytes = ArrayList<Byte>()
            val buffer = ByteArray(4096)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) return null
                for (i in 0 until read) bytes.add(buffer[i])
            }
            val array = ByteArray(bytes.size) { index -> bytes[index] }
            return String(array, StandardCharsets.UTF_8)
        }
    }

    private fun writeActiveVersionAtomically(pluginId: String, version: Int) {
        val dir = File(pluginRoot, "installed/$pluginId").apply { mkdirs() }
        val target = File(dir, "active-version")
        val temp = File(dir, ".active-version.tmp")
        temp.writeText(version.toString())
        if (target.exists() && !target.delete()) throw IOException("Unable to replace active plugin pointer")
        if (!temp.renameTo(target)) throw IOException("Unable to activate installed plugin version")
    }

    private fun pruneOldVersions(pluginId: String, keep: Set<Int>) {
        val versionsDir = File(pluginRoot, "installed/$pluginId/versions")
        versionsDir.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> dir.name.toIntOrNull()?.let { it to dir } }
            .sortedByDescending { it.first }
            .drop(2)
            .filterNot { it.first in keep }
            .forEach { it.second.deleteRecursively() }
    }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles().orEmpty().forEach { child -> copyDirectory(child, File(target, child.name)) }
        } else {
            source.copyTo(target, overwrite = true)
        }
    }
}
