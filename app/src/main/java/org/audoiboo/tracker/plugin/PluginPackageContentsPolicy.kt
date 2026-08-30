package org.audoiboo.tracker.plugin

import java.io.File

const val MAX_PLUGIN_ENTRYPOINT_BYTES = 256L * 1024L

/**
 * Validates the extracted package tree before a version can become active.
 * This complements archive path checks with checks that depend on the final filesystem layout.
 */
object PluginPackageContentsPolicy {
    private val forbiddenExtensions = setOf("apk", "dex", "jar", "class", "so")

    fun validate(
        manifest: PluginPackageManifest,
        packageDir: File,
        maxEntrypointBytes: Long = MAX_PLUGIN_ENTRYPOINT_BYTES
    ): PluginManifestValidation {
        val errors = buildList {
            if (!packageDir.isDirectory) {
                add("Plugin package directory is missing")
                return@buildList
            }

            val root = runCatching { packageDir.canonicalFile }.getOrElse {
                add("Plugin package directory cannot be resolved")
                return@buildList
            }
            val rootPath = root.toPath()

            root.walkTopDown().forEach { file ->
                if (file == root || !file.isFile) return@forEach
                val canonical = runCatching { file.canonicalFile }.getOrNull()
                if (canonical == null || !canonical.toPath().startsWith(rootPath)) {
                    add("Package file escapes plugin directory: ${file.name}")
                    return@forEach
                }
                val extension = canonical.extension.lowercase()
                if (extension in forbiddenExtensions) {
                    add("Executable plugin payload is not allowed: ${canonical.name}")
                }
            }

            manifest.entrypoints.forEach { (name, relativePath) ->
                if (!PluginPackagePolicy.isSafeRelativePath(relativePath)) {
                    add("Unsafe entrypoint path: $relativePath")
                    return@forEach
                }
                val entrypoint = runCatching { File(root, relativePath).canonicalFile }.getOrNull()
                if (entrypoint == null || !entrypoint.toPath().startsWith(rootPath)) {
                    add("Entrypoint escapes plugin directory: $name")
                    return@forEach
                }
                if (!entrypoint.isFile) {
                    add("Missing entrypoint file: $relativePath")
                    return@forEach
                }
                if (entrypoint.length() > maxEntrypointBytes) {
                    add("Entrypoint file is too large: $relativePath")
                }
            }
        }.distinct()
        return PluginManifestValidation(errors.isEmpty(), errors)
    }
}
