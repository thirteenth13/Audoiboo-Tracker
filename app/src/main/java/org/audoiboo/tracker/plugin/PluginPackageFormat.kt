package org.audoiboo.tracker.plugin

const val PLUGIN_PACKAGE_EXTENSION = "abplugin"
const val PLUGIN_MANIFEST_FILE = "plugin.json"

enum class PluginRuntime {
    DECLARATIVE,
    JAVASCRIPT
}

data class PluginPermissions(
    val networkHosts: Set<String> = emptySet(),
    val cookies: Boolean = false,
    val javascript: Boolean = false
)

data class PluginPackageManifest(
    val id: String,
    val name: String,
    val version: Int,
    val apiVersion: Int,
    val runtime: PluginRuntime = PluginRuntime.DECLARATIVE,
    val hosts: Set<String>,
    val capabilities: Set<SourceCapability>,
    val permissions: PluginPermissions = PluginPermissions(),
    val entrypoints: Map<String, String> = emptyMap()
)

data class PluginManifestValidation(
    val valid: Boolean,
    val errors: List<String>
)

object PluginPackagePolicy {
    private val idRegex = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
    private val hostRegex = Regex("^[a-z0-9.-]+$")

    fun validate(manifest: PluginPackageManifest): PluginManifestValidation {
        val errors = buildList {
            if (!idRegex.matches(manifest.id)) add("Invalid plugin id")
            if (manifest.name.isBlank()) add("Plugin name must not be blank")
            if (manifest.version <= 0) add("Plugin version must be positive")
            if (manifest.apiVersion <= 0) add("Plugin API version must be positive")
            if (manifest.apiVersion != SOURCE_PLUGIN_API_VERSION) add("Unsupported plugin API version")
            if (manifest.hosts.isEmpty()) add("Plugin must declare at least one host")
            manifest.hosts.forEach { host ->
                if (!isValidHost(host)) add("Invalid host: $host")
            }
            manifest.permissions.networkHosts.forEach { host ->
                if (!isValidHost(host)) add("Invalid network permission host: $host")
            }
            if (!manifest.permissions.networkHosts.containsAll(manifest.hosts)) {
                add("All source hosts must be included in networkHosts permissions")
            }
            if (manifest.runtime == PluginRuntime.JAVASCRIPT && !manifest.permissions.javascript) {
                add("JavaScript runtime requires javascript permission")
            }
            manifest.entrypoints.forEach { (name, path) ->
                if (name.isBlank()) add("Entrypoint name must not be blank")
                if (!isSafeRelativePath(path)) add("Unsafe entrypoint path: $path")
            }
        }.distinct()
        return PluginManifestValidation(errors.isEmpty(), errors)
    }

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\')) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false
        val normalized = path.replace('\\', '/')
        val parts = normalized.split('/')
        return parts.none { it.isBlank() || it == "." || it == ".." }
    }

    fun isPluginPackageName(name: String): Boolean =
        name.substringAfterLast('.', missingDelimiterValue = "").equals(PLUGIN_PACKAGE_EXTENSION, ignoreCase = true)

    private fun isValidHost(host: String): Boolean {
        val normalized = host.lowercase().trim()
        return normalized == host &&
            normalized.length in 3..253 &&
            hostRegex.matches(normalized) &&
            !normalized.startsWith('.') &&
            !normalized.endsWith('.') &&
            !normalized.contains("..")
    }
}
