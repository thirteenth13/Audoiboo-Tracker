package org.audoiboo.tracker.plugin

import org.json.JSONObject
import java.io.File

/** Host-owned package runtime and active source registry. */
object PluginPackageRuntime {
    @Volatile private var initialized = false
    @Volatile private var pluginRootRef: File? = null
    @Volatile private var storeRef: PluginPackageStore? = null
    @Volatile private var installerRef: PluginPackageInstaller? = null
    @Volatile private var lastScanRef: PluginStoreScanResult? = null
    @Volatile private var declarativeRuntimeRef: DeclarativePluginRuntime? = null
    @Volatile private var runtimeHealthRef: PluginRuntimeHealth? = null

    val store: PluginPackageStore?
        get() = storeRef
    val lastScan: PluginStoreScanResult?
        get() = lastScanRef
    val registry: SourcePluginRegistry
        get() = BuiltInSourcePluginManager.instance.activeRegistry()
    val registrations: List<SourcePluginRegistration>
        get() = BuiltInSourcePluginManager.instance.registrations()

    fun initialize(appFilesDir: File, transport: PluginHttpTransport = HostPluginHttpTransport): PluginStoreScanResult = synchronized(this) {
        if (initialized) return lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        val root = File(appFilesDir, "plugins")
        val manager = BuiltInSourcePluginManager.instance
        val store = PluginPackageStore(root, manager)
        val runtime = DeclarativePluginRuntime(PluginSandbox(transport))
        pluginRootRef = root
        storeRef = store
        installerRef = PluginPackageInstaller(root, manager)
        declarativeRuntimeRef = runtime
        runtimeHealthRef = PluginRuntimeHealth(File(root, "runtime-health"))
        initialized = true
        rescanAndActivate(manager, store, runtime)
    }

    fun installPackage(packageFile: File): PluginInstallResult = synchronized(this) {
        val installer = installerRef ?: return PluginInstallResult.Failed("Plugin runtime is not initialized")
        val result = installer.install(packageFile)
        if (result is PluginInstallResult.Installed) {
            runtimeHealthRef?.clear(result.registration.packageId, result.registration.descriptor?.version)
            rescanCurrent()
        }
        result
    }

    fun enablePackage(pluginId: String): Boolean = synchronized(this) {
        val store = storeRef ?: return false
        val runtime = declarativeRuntimeRef ?: return false
        val manager = BuiltInSourcePluginManager.instance
        val registration = manager.packageRegistration(pluginId) ?: return false
        registration.descriptor?.version?.let { runtimeHealthRef?.clear(pluginId, it) }
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
        val activeVersion = store.readActiveVersion(pluginId)
        val moved = store.quarantineActive(pluginId, reason)
        if (moved) {
            if (activeVersion != null) runtimeHealthRef?.clear(pluginId, activeVersion)
            rescanCurrent()
        }
        moved
    }

    fun restorePackage(pluginId: String): Boolean = synchronized(this) {
        val store = storeRef ?: return false
        val restored = store.restoreLatestQuarantined(pluginId)
        if (restored) {
            store.readActiveVersion(pluginId)?.let { runtimeHealthRef?.clear(pluginId, it) }
            rescanCurrent()
        }
        restored
    }

    fun rollbackPackage(pluginId: String): Boolean = synchronized(this) {
        val installer = installerRef ?: return false
        val rolledBack = installer.rollback(pluginId)
        if (rolledBack) {
            storeRef?.readActiveVersion(pluginId)?.let { runtimeHealthRef?.clear(pluginId, it) }
            rescanCurrent()
        }
        rolledBack
    }

    private fun rescanCurrent(): PluginStoreScanResult {
        val store = storeRef ?: return PluginStoreScanResult(emptyList(), emptyList(), emptyList(), listOf("Plugin store unavailable"))
        val runtime = declarativeRuntimeRef ?: return PluginStoreScanResult(emptyList(), emptyList(), emptyList(), listOf("Plugin runtime unavailable"))
        return rescanAndActivate(BuiltInSourcePluginManager.instance, store, runtime)
    }

    private fun rescanAndActivate(manager: SourcePluginManager, store: PluginPackageStore, runtime: DeclarativePluginRuntime): PluginStoreScanResult {
        val result = store.scanInstalled()
        result.registrations.filter { store.isEnabled(it.packageId) }.forEach { registration ->
            activateRegistration(manager, registration, runtime)
        }
        return result.copy(registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE })
            .also { lastScanRef = it }
    }

    private fun activateRegistration(manager: SourcePluginManager, registration: SourcePluginRegistration, runtime: DeclarativePluginRuntime): Boolean {
        val manifest = registration.manifest ?: return false
        val path = registration.packagePath ?: return false
        if (manifest.runtime != PluginRuntime.DECLARATIVE) return false
        val installedDir = File(path)
        if (!installedDir.isDirectory) return false
        val packageDir = runtimeCompatiblePackageDir(manifest, installedDir)
        val plugin = runCatching {
            DeclarativeSourcePlugin(
                manifest = manifest,
                packageDir = packageDir,
                runtime = runtime,
                onRuntimeSuccess = ::handleRuntimeSuccess,
                onRuntimeFailure = ::handleRuntimeFailure
            )
        }.getOrNull() ?: return false
        return manager.enablePackagePlugin(registration.packageId, plugin) != null
    }

    /**
     * Compatibility fixes live in a disposable runtime copy, never inside the installed package.
     * Izib v1 follows a series link even when the input is already /serie<id>; that turns a valid
     * series page into an unrelated navigation target. Removing followLink restores direct parsing.
     */
    private fun runtimeCompatiblePackageDir(manifest: PluginPackageManifest, installedDir: File): File {
        if (manifest.id != "izib" || manifest.version > 1) return installedDir
        val root = pluginRootRef ?: return installedDir
        val patched = File(root, "runtime-patches/${manifest.id}-${manifest.version}")
        runCatching {
            if (patched.exists()) patched.deleteRecursively()
            installedDir.copyRecursively(patched, overwrite = true)
            val relative = manifest.entrypoints["seriesLookup"] ?: return@runCatching
            val rule = File(patched, relative)
            val json = JSONObject(rule.readText())
            json.getJSONObject("series").remove("followLink")
            rule.writeText(json.toString(2))
        }.onFailure {
            patched.deleteRecursively()
            return installedDir
        }
        return patched.takeIf { it.isDirectory } ?: installedDir
    }

    private fun handleRuntimeSuccess(pluginId: String, version: Int) {
        runtimeHealthRef?.recordSuccess(pluginId, version)
    }

    private fun handleRuntimeFailure(pluginId: String, version: Int, failure: Throwable) = synchronized(this) {
        val health = runtimeHealthRef ?: return@synchronized
        val reason = failure.message ?: failure::class.java.simpleName
        val state = runCatching { health.recordFailure(pluginId, version, reason) }.getOrNull() ?: return@synchronized
        if (!health.shouldQuarantine(state)) return@synchronized
        val store = storeRef ?: return@synchronized
        if (store.readActiveVersion(pluginId) != version) {
            health.clear(pluginId, version)
            return@synchronized
        }
        val wasEnabled = store.isEnabled(pluginId)
        val quarantineReason = "Automatic quarantine after ${state.failures} runtime failures: ${state.lastReason}"
        if (!store.quarantineActive(pluginId, quarantineReason)) return@synchronized
        health.clear(pluginId, version)
        if (wasEnabled && store.readActiveVersion(pluginId) != null) store.enable(pluginId)
        rescanCurrent()
    }

    private fun refreshSnapshot(manager: SourcePluginManager) {
        val current = lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        lastScanRef = current.copy(registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE })
    }
}
