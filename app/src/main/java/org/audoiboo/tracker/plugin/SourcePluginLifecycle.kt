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
    val descriptor: SourceDescriptor,
    val origin: PluginOrigin,
    val state: PluginState,
    val packagePath: String? = null,
    val failureReason: String? = null
)

class SourcePluginManager(
    builtIns: Collection<SourcePlugin>
) {
    private val builtInsById = builtIns.associateBy { it.descriptor.id }.also { indexed ->
        require(indexed.size == builtIns.size) { "Duplicate built-in source plugin id" }
    }

    private val packageRegistrations = linkedMapOf<String, SourcePluginRegistration>()

    fun activeRegistry(): SourcePluginRegistry = SourcePluginRegistry(
        builtInsById.values + packageRegistrations.values
            .asSequence()
            .filter { it.state == PluginState.ENABLED }
            // Executable package plugins are intentionally not instantiated until the sandbox loader exists.
            .mapNotNull { null as SourcePlugin? }
            .toList()
    )

    fun registrations(): List<SourcePluginRegistration> {
        val builtIns = builtInsById.values.map { plugin ->
            SourcePluginRegistration(
                descriptor = plugin.descriptor,
                origin = PluginOrigin.BUILT_IN,
                state = PluginState.ENABLED
            )
        }
        return (builtIns + packageRegistrations.values)
            .sortedWith(compareBy<SourcePluginRegistration> { it.descriptor.name.lowercase() }.thenBy { it.descriptor.id })
    }

    fun registerPackageManifest(
        manifest: PluginPackageManifest,
        packagePath: String
    ): SourcePluginRegistration {
        val validation = PluginPackagePolicy.validate(manifest)
        val descriptor = SourceDescriptor(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            apiVersion = manifest.apiVersion,
            hosts = manifest.hosts,
            capabilities = manifest.capabilities
        )
        val registration = when {
            manifest.id in builtInsById -> SourcePluginRegistration(
                descriptor = descriptor,
                origin = PluginOrigin.PACKAGE,
                state = PluginState.QUARANTINED,
                packagePath = packagePath,
                failureReason = "Package id conflicts with a built-in plugin"
            )
            !validation.valid -> SourcePluginRegistration(
                descriptor = descriptor,
                origin = PluginOrigin.PACKAGE,
                state = if (manifest.apiVersion != SOURCE_PLUGIN_API_VERSION) PluginState.INCOMPATIBLE else PluginState.QUARANTINED,
                packagePath = packagePath,
                failureReason = validation.errors.joinToString("; ")
            )
            else -> SourcePluginRegistration(
                descriptor = descriptor,
                origin = PluginOrigin.PACKAGE,
                state = PluginState.DISABLED,
                packagePath = packagePath,
                failureReason = "Package validated; executable loader is not enabled yet"
            )
        }
        packageRegistrations[manifest.id] = registration
        return registration
    }
}

object BuiltInSourcePluginManager {
    val instance: SourcePluginManager by lazy {
        SourcePluginManager(listOf(AudiobooSourcePlugin))
    }

    val registry: SourcePluginRegistry
        get() = instance.activeRegistry()
}
