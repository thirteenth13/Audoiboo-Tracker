package org.audoiboo.tracker.plugin

import java.io.File

/** Host-owned package runtime and active source registry. */
object PluginPackageRuntime {
    @Volatile private var initialized = false
    @Volatile private var storeRef: PluginPackageStore? = null
    @Volatile private var lastScanRef: PluginStoreScanResult? = null
    @Volatile private var declarativeRuntimeRef: DeclarativePluginRuntime? = null

    val store: PluginPackageStore?
        get() = storeRef

    val lastScan: PluginStoreScanResult?
        get() = lastScanRef

    val registry: SourcePluginRegistry
        get() = BuiltInSourcePluginManager.instance.activeRegistry()

    fun initialize(
        appFilesDir: File,
        transport: PluginHttpTransport = HostPluginHttpTransport
    ): PluginStoreScanResult = synchronized(this) {
        if (initialized) return lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        val root = File(appFilesDir, "plugins")
        val manager = BuiltInSourcePluginManager.instance
        val store = PluginPackageStore(root, manager)
        val result = store.scanInstalled()
        val runtime = DeclarativePluginRuntime(PluginSandbox(transport))

        result.registrations
            .filter { store.isEnabled(it.packageId) }
            .forEach { registration -> activateRegistration(manager, registration, runtime) }

        storeRef = store
        declarativeRuntimeRef = runtime
        lastScanRef = result.copy(
            registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE }
        )
        initialized = true
        lastScanRef!!
    }

    fun enablePackage(pluginId: String): Boolean = synchronized(this) {
        val store = storeRef ?: return false
        val runtime = declarativeRuntimeRef ?: return false
        val manager = BuiltInSourcePluginManager.instance
        val registration = manager.packageRegistration(pluginId) ?: return false
        if (!store.enable(pluginId)) return false
        val enabled = activateRegistration(manager, registration, runtime)
        if (!enabled) store.disable(pluginId)
        refreshSnapshot(manager)
        enabled
    }

    fun disablePackage(pluginId: String): Boolean = synchronized(this) {
        val store = storeRef ?: return false
        val disabled = store.disable(pluginId)
        refreshSnapshot(BuiltInSourcePluginManager.instance)
        disabled
    }

    private fun activateRegistration(
        manager: SourcePluginManager,
        registration: SourcePluginRegistration,
        runtime: DeclarativePluginRuntime
    ): Boolean {
        val manifest = registration.manifest ?: return false
        val path = registration.packagePath ?: return false
        if (manifest.runtime != PluginRuntime.DECLARATIVE) return false
        val packageDir = File(path)
        if (!packageDir.isDirectory) return false
        val plugin = runCatching { DeclarativeSourcePlugin(manifest, packageDir, runtime) }.getOrNull() ?: return false
        return manager.enablePackagePlugin(registration.packageId, plugin) != null
    }

    private fun refreshSnapshot(manager: SourcePluginManager) {
        val current = lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        lastScanRef = current.copy(
            registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE }
        )
    }
}
