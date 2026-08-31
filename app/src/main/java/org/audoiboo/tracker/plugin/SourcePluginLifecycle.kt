package org.audoiboo.tracker.plugin

enum class PluginOrigin {
    BUILT_IN,
    PACKAGE
}

enum class PluginState {
    ENABLED,
    DISABLED,
    QUARANTINED,
    INCOMPATIBLE
}

data class SourcePluginRegistration(
    val descriptor: SourceDescriptor?,
    val packageId: String,
    val displayName: String,
    val origin: PluginOrigin,
    val state: PluginState,
    val packagePath: String? = null,
    val manifest: PluginPackageManifest? = null,
    val failureReason: String? = null
)

class SourcePluginManager(
    builtIns: Collection<SourcePlugin>
) {
    private val builtInsById = builtIns.associateBy { it.descriptor.id }.also { indexed ->
        require(indexed.size == builtIns.size) { "Duplicate built-in source plugin id" }
    }

    private val packageRegistrations = linkedMapOf<String, SourcePluginRegistration>()
    private val activePackagePlugins = linkedMapOf<String, SourcePlugin>()

    fun activeRegistry(): SourcePluginRegistry =
        SourcePluginRegistry(builtInsById.values + activePackagePlugins.values)

    fun registrations(): List<SourcePluginRegistration> {
        val builtIns = builtInsById.values.map { plugin ->
            SourcePluginRegistration(
                descriptor = plugin.descriptor,
                packageId = plugin.descriptor.id,
                displayName = plugin.descriptor.name,
                origin = PluginOrigin.BUILT_IN,
                state = PluginState.ENABLED
            )
        }
        return (builtIns + packageRegistrations.values)
            .sortedWith(compareBy<SourcePluginRegistration> { it.displayName.lowercase() }.thenBy { it.packageId })
    }

    fun packageRegistration(id: String): SourcePluginRegistration? = packageRegistrations[id]

    fun clearPackageRegistrations() {
        packageRegistrations.clear()
        activePackagePlugins.clear()
    }

    fun markPackageState(id: String, state: PluginState, reason: String? = null): SourcePluginRegistration? {
        val current = packageRegistrations[id] ?: return null
        require(current.origin == PluginOrigin.PACKAGE)
        if (state != PluginState.ENABLED) activePackagePlugins.remove(id)
        return current.copy(state = state, failureReason = reason).also { packageRegistrations[id] = it }
    }

    fun enablePackagePlugin(id: String, plugin: SourcePlugin): SourcePluginRegistration? {
        val current = packageRegistrations[id] ?: return null
        if (current.origin != PluginOrigin.PACKAGE || current.state == PluginState.QUARANTINED || current.state == PluginState.INCOMPATIBLE) {
            return null
        }
        if (plugin.descriptor.id != id || plugin.descriptor.apiVersion != SOURCE_PLUGIN_API_VERSION) return null
        activePackagePlugins[id] = plugin
        return current.copy(state = PluginState.ENABLED, failureReason = null).also { packageRegistrations[id] = it }
    }

    fun disablePackagePlugin(id: String, reason: String = "Disabled by user"): SourcePluginRegistration? =
        markPackageState(id, PluginState.DISABLED, reason)

    fun registerPackageManifest(
        manifest: PluginPackageManifest,
        packagePath: String
    ): SourcePluginRegistration {
        val validation = PluginPackagePolicy.validate(manifest)
        val descriptor = if (validation.valid) {
            SourceDescriptor(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                apiVersion = manifest.apiVersion,
                hosts = manifest.hosts,
                capabilities = manifest.capabilities
            )
        } else null

        val registration = when {
            manifest.id in builtInsById -> SourcePluginRegistration(
                descriptor = descriptor,
                packageId = manifest.id,
                displayName = manifest.name,
                origin = PluginOrigin.PACKAGE,
                state = PluginState.QUARANTINED,
                packagePath = packagePath,
                manifest = manifest,
                failureReason = "Package id conflicts with a built-in plugin"
            )
            !validation.valid -> SourcePluginRegistration(
                descriptor = null,
                packageId = manifest.id,
                displayName = manifest.name,
                origin = PluginOrigin.PACKAGE,
                state = if (manifest.apiVersion != SOURCE_PLUGIN_API_VERSION) PluginState.INCOMPATIBLE else PluginState.QUARANTINED,
                packagePath = packagePath,
                manifest = manifest,
                failureReason = validation.errors.joinToString("; ")
            )
            else -> SourcePluginRegistration(
                descriptor = descriptor,
                packageId = manifest.id,
                displayName = manifest.name,
                origin = PluginOrigin.PACKAGE,
                state = PluginState.DISABLED,
                packagePath = packagePath,
                manifest = manifest,
                failureReason = "Package validated; waiting for user activation"
            )
        }
        activePackagePlugins.remove(manifest.id)
        packageRegistrations[manifest.id] = registration
        return registration
    }
}

object BuiltInSourcePluginManager {
    val instance: SourcePluginManager by lazy {
        SourcePluginManager(
            listOf(
                AudiobooSourcePlugin,
                OpenLibraryMetadataPlugin,
                GoogleBooksCatalogPlugin,
                FantLabCatalogPlugin
            )
        )
    }

    val registry: SourcePluginRegistry
        get() = instance.activeRegistry()
}
