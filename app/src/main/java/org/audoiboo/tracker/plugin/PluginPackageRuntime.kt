package org.audoiboo.tracker.plugin

import java.io.File

/** Host-owned package runtime and active source registry. */
object PluginPackageRuntime {
    @Volatile private var initialized = false
    @Volatile private var pluginRootRef: File? = null
    @Volatile private var storeRef: PluginPackageStore? = null
    @Volatile private var installerRef: PluginPackageInstaller? = null
    @Volatile private var lastScanRef: PluginStoreScanResult? = null
    @Volatile private var declarativeRuntimeRef: DeclarativePluginRuntime? = null

    val store: PluginPackageStore?
        get() = storeRef

    val lastScan: PluginStoreScanResult?
        get() = lastScanRef

    val registry: SourcePluginRegistry
        get() = BuiltInSourcePluginManager.instance.activeRegistry()

    val registrations: List<SourcePluginRegistration>
        get() = BuiltInSourcePluginManager.instance.registrations()

    fun initialize(
        appFilesDir: File,
        transport: PluginHttpTransport = HostPluginHttpTransport
    ): PluginStoreScanResult = synchronized(this) {
        if (initialized) return lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        val root = File(appFilesDir, "plugins")
        val manager = BuiltInSourcePluginManager.instance
        val store = PluginPackageStore(root, manager)
        val runtime = DeclarativePluginRuntime(PluginSandbox(transport))
        pluginRootRef = root
        storeRef = store
        installerRef = PluginPackageInstaller(root, manager)
        declarativeRuntimeRef = runtime
        initialized = true
        rescanAndActivate(manager, store, runtime)
    }

    fun installPackage(packageFile: File): PluginInstallResult = synchronized(this) {
        val installer = installerRef ?: return PluginInstallResult.Failed("Plugin runtime is not initialized")
        val result = installer.install(packageFile)
        if (result is PluginInstallResult.Installed) {
            rescanCurrent()
        }
        result
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

    fun quarantinePackage(pluginId: String, reason: String = "Quarantined by user"): Boolean = synchronized(this) {
        val store = storeRef ?: return false
        val moved = store.quarantineActive(pluginId, reason)
        if (moved) {
            rescanCurrent()
        }
        moved
    }

    fun restorePackage(pluginId: String): Boolean = synchronized(this) {
        val store = storeRef ?: return false
        val restored = store.restoreLatestQuarantined(pluginId)
        if (restored) {
            rescanCurrent()
        }
        restored
    }

    fun rollbackPackage(pluginId: String): Boolean = synchronized(this) {
        val installer = installerRef ?: return false
        val rolledBack = installer.rollback(pluginId)
        if (rolledBack) {
            rescanCurrent()
        }
        rolledBack
    }

    private fun rescanCurrent(): PluginStoreScanResult {
        val store = storeRef ?: return PluginStoreScanResult(emptyList(), emptyList(), emptyList(), listOf("Plugin store unavailable"))
        val runtime = declarativeRuntimeRef ?: return PluginStoreScanResult(emptyList(), emptyList(), emptyList(), listOf("Plugin runtime unavailable"))
        return rescanAndActivate(BuiltInSourcePluginManager.instance, store, runtime)
    }

    private fun rescanAndActivate(
        manager: SourcePluginManager,
        store: PluginPackageStore,
        runtime: DeclarativePluginRuntime
    ): PluginStoreScanResult {
        val result = store.scanInstalled()
        result.registrations
            .filter { store.isEnabled(it.packageId) }
            .forEach { registration -> activateRegistration(manager, registration, runtime) }
        return result.copy(
            registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE }
        ).also { lastScanRef = it }
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
