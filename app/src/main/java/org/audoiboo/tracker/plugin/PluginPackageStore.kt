package org.audoiboo.tracker.plugin

import java.io.File
import java.io.IOException

/** Snapshot produced while rebuilding package state from disk after process/app restart. */
data class PluginStoreScanResult(
    val registrations: List<SourcePluginRegistration>,
    val recoveredPlugins: List<String>,
    val quarantinedPlugins: List<String>,
    val errors: List<String>
)

/**
 * Owns durable package layout and recovery. It never executes plugin code.
 *
 * Layout:
 * plugins/
 *   installed/<id>/active-version
 *   installed/<id>/enabled
 *   installed/<id>/versions/<version>/plugin.json
 *   quarantine/<id>/<version>-<timestamp>/...
 */
class PluginPackageStore(
    private val pluginRoot: File,
    private val manager: SourcePluginManager,
    private val manifestDecoder: PluginManifestDecoder = JsonPluginManifestDecoder,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    fun scanInstalled(): PluginStoreScanResult {
        manager.clearPackageRegistrations()
        val recovered = mutableListOf<String>()
        val quarantined = mutableListOf<String>()
        val errors = mutableListOf<String>()

        installedRoot().listFiles().orEmpty()
            .filter { it.isDirectory }
            .sortedBy { it.name }
            .forEach { pluginDir ->
                val pluginId = pluginDir.name
                val versions = availableVersions(pluginId)
                if (versions.isEmpty()) {
                    activeVersionFile(pluginId).delete()
                    enabledMarker(pluginId).delete()
                    errors += "$pluginId: no installed versions"
                    return@forEach
                }

                val requestedActive = readActiveVersion(pluginId)
                val candidates = buildList {
                    if (requestedActive != null && requestedActive in versions) add(requestedActive)
                    addAll(versions.filterNot { it == requestedActive }.sortedDescending())
                }

                var registration: SourcePluginRegistration? = null
                var selectedVersion: Int? = null
                for (version in candidates) {
                    when (val loaded = loadInstalledVersion(pluginId, version)) {
                        is LoadResult.Valid -> {
                            val candidate = manager.registerPackageManifest(loaded.manifest, loaded.directory.absolutePath)
                            if (candidate.state == PluginState.QUARANTINED || candidate.state == PluginState.INCOMPATIBLE) {
                                val reason = candidate.failureReason ?: "package rejected"
                                errors += "$pluginId@$version: $reason"
                                quarantineVersion(pluginId, version, reason)
                                quarantined += "$pluginId@$version"
                                continue
                            }
                            registration = candidate
                            selectedVersion = version
                            break
                        }
                        is LoadResult.Invalid -> {
                            errors += "$pluginId@$version: ${loaded.reason}"
                            quarantineVersion(pluginId, version, loaded.reason)
                            quarantined += "$pluginId@$version"
                        }
                    }
                }

                if (registration == null || selectedVersion == null) {
                    activeVersionFile(pluginId).delete()
                    enabledMarker(pluginId).delete()
                    manager.markPackageState(pluginId, PluginState.QUARANTINED, "No valid installed version")
                    return@forEach
                }

                if (requestedActive != selectedVersion) {
                    writeActiveVersionAtomically(pluginId, selectedVersion)
                    recovered += pluginId
                }
            }

        return PluginStoreScanResult(
            registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE },
            recoveredPlugins = recovered.distinct(),
            quarantinedPlugins = quarantined.distinct(),
            errors = errors
        )
    }

    fun enable(pluginId: String): Boolean {
        if (!File(installedRoot(), pluginId).isDirectory) return false
        enabledMarker(pluginId).apply {
            parentFile?.mkdirs()
            writeText("enabled")
        }
        disabledMarker(pluginId).delete()
        return true
    }

    fun disable(pluginId: String): Boolean {
        if (!File(installedRoot(), pluginId).isDirectory) return false
        enabledMarker(pluginId).delete()
        disabledMarker(pluginId).apply {
            parentFile?.mkdirs()
            writeText("disabled")
        }
        manager.disablePackagePlugin(pluginId)
        return true
    }

    fun clearDisabled(pluginId: String): Boolean {
        val marker = disabledMarker(pluginId)
        if (!marker.exists()) return true
        return marker.delete()
    }

    fun isEnabled(pluginId: String): Boolean = enabledMarker(pluginId).isFile && !disabledMarker(pluginId).isFile

    fun isDisabled(pluginId: String): Boolean = disabledMarker(pluginId).isFile

    fun quarantineActive(pluginId: String, reason: String): Boolean {
        val active = readActiveVersion(pluginId) ?: return false
        val moved = quarantineVersion(pluginId, active, reason)
        if (!moved) return false
        enabledMarker(pluginId).delete()
        val fallback = availableVersions(pluginId).maxOrNull()
        if (fallback != null) writeActiveVersionAtomically(pluginId, fallback)
        else activeVersionFile(pluginId).delete()
        manager.markPackageState(pluginId, PluginState.QUARANTINED, reason)
        return true
    }

    fun restoreLatestQuarantined(pluginId: String): Boolean {
        val pluginQuarantine = File(quarantineRoot(), pluginId)
        val candidate = pluginQuarantine.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> dir.name.substringBefore('-').toIntOrNull()?.let { it to dir } }
            .maxWithOrNull(compareBy<Pair<Int, File>> { it.first }.thenBy { it.second.lastModified() })
            ?: return false
        val (version, sourceDir) = candidate
        val destination = versionDir(pluginId, version)
        if (destination.exists()) return false
        destination.parentFile?.mkdirs()
        if (!sourceDir.renameTo(destination)) {
            sourceDir.copyRecursively(destination, overwrite = false)
            if (!sourceDir.deleteRecursively()) {
                destination.deleteRecursively()
                return false
            }
        }
        when (val loaded = loadInstalledVersion(pluginId, version)) {
            is LoadResult.Invalid -> {
                destination.deleteRecursively()
                return false
            }
            is LoadResult.Valid -> {
                val registration = manager.registerPackageManifest(loaded.manifest, destination.absolutePath)
                if (registration.state == PluginState.QUARANTINED || registration.state == PluginState.INCOMPATIBLE) {
                    destination.deleteRecursively()
                    return false
                }
            }
        }
        writeActiveVersionAtomically(pluginId, version)
        return true
    }

    fun readActiveVersion(pluginId: String): Int? = activeVersionFile(pluginId)
        .takeIf { it.isFile }
        ?.readText()
        ?.trim()
        ?.toIntOrNull()

    private fun loadInstalledVersion(pluginId: String, version: Int): LoadResult {
        val dir = versionDir(pluginId, version)
        val manifestFile = File(dir, PLUGIN_MANIFEST_FILE)
        if (!manifestFile.isFile) return LoadResult.Invalid("Missing $PLUGIN_MANIFEST_FILE")
        val manifest = runCatching { manifestDecoder.decode(manifestFile.readText()) }
            .getOrElse { return LoadResult.Invalid("Invalid manifest: ${it.message ?: "parse error"}") }
        if (manifest.id != pluginId) return LoadResult.Invalid("Manifest id ${manifest.id} does not match directory $pluginId")
        if (manifest.version != version) return LoadResult.Invalid("Manifest version ${manifest.version} does not match directory $version")
        val validation = PluginPackagePolicy.validate(manifest)
        if (!validation.valid) return LoadResult.Invalid(validation.errors.joinToString("; "))
        return LoadResult.Valid(manifest, dir)
    }

    private fun quarantineVersion(pluginId: String, version: Int, reason: String): Boolean {
        val source = versionDir(pluginId, version)
        if (!source.isDirectory) return false
        val destination = File(quarantineRoot(), "$pluginId/$version-${clockMillis()}")
        destination.parentFile?.mkdirs()
        val moved = source.renameTo(destination) || runCatching {
            source.copyRecursively(destination, overwrite = false)
            source.deleteRecursively()
        }.getOrDefault(false)
        if (!moved) return false
        runCatching { File(destination, "quarantine-reason.txt").writeText(reason.take(4096)) }
        return true
    }

    private fun availableVersions(pluginId: String): List<Int> = versionsDir(pluginId)
        .listFiles().orEmpty()
        .filter { it.isDirectory }
        .mapNotNull { it.name.toIntOrNull() }
        .distinct()
        .sortedDescending()

    private fun writeActiveVersionAtomically(pluginId: String, version: Int) {
        val pluginDir = File(installedRoot(), pluginId).apply { mkdirs() }
        val target = activeVersionFile(pluginId)
        val temp = File(pluginDir, ".active-version.tmp")
        temp.writeText(version.toString())
        if (target.exists() && !target.delete()) throw IOException("Unable to replace active plugin pointer")
        if (!temp.renameTo(target)) throw IOException("Unable to activate plugin version")
    }

    private fun installedRoot() = File(pluginRoot, "installed")
    private fun quarantineRoot() = File(pluginRoot, "quarantine")
    private fun versionsDir(pluginId: String) = File(installedRoot(), "$pluginId/versions")
    private fun versionDir(pluginId: String, version: Int) = File(versionsDir(pluginId), version.toString())
    private fun activeVersionFile(pluginId: String) = File(installedRoot(), "$pluginId/active-version")
    private fun enabledMarker(pluginId: String) = File(installedRoot(), "$pluginId/enabled")
    private fun disabledMarker(pluginId: String) = File(installedRoot(), "$pluginId/disabled")

    private sealed interface LoadResult {
        data class Valid(val manifest: PluginPackageManifest, val directory: File) : LoadResult
        data class Invalid(val reason: String) : LoadResult
    }
}
