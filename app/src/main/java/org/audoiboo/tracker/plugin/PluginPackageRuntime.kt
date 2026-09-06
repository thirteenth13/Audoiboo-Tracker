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

    val store: PluginPackageStore? get() = storeRef
    val lastScan: PluginStoreScanResult? get() = lastScanRef
    val registry: SourcePluginRegistry
        get() {
            val active = BuiltInSourcePluginManager.instance.activeRegistry().plugins
            return SourcePluginRegistry(active + CatalogLibrarySourcePlugin)
        }
    val registrations: List<SourcePluginRegistration> get() = BuiltInSourcePluginManager.instance.registrations()

    /** Returns the active installed package directory, including compatibility patches when needed. */
    fun packageDirectory(pluginId: String): File? = synchronized(this) {
        val registration = BuiltInSourcePluginManager.instance.packageRegistration(pluginId) ?: return@synchronized null
        val manifest = registration.manifest ?: return@synchronized null
        val path = registration.packagePath ?: return@synchronized null
        runtimeCompatiblePackageDir(manifest, File(path)).takeIf { it.isDirectory }
    }

    fun initialize(appFilesDir: File, transport: PluginHttpTransport = HostPluginHttpTransport): PluginStoreScanResult = synchronized(this) {
        if (initialized) return lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        val root = File(appFilesDir, "plugins")
        val manager = BuiltInSourcePluginManager.instance
        val store = PluginPackageStore(root, manager)
        val runtime = DeclarativePluginRuntime(PluginSandbox(transport))
        pluginRootRef = root; storeRef = store; installerRef = PluginPackageInstaller(root, manager)
        declarativeRuntimeRef = runtime; runtimeHealthRef = PluginRuntimeHealth(File(root, "runtime-health")); initialized = true
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
        val store = storeRef ?: return false; val runtime = declarativeRuntimeRef ?: return false
        val manager = BuiltInSourcePluginManager.instance
        val registration = manager.packageRegistration(pluginId) ?: return false
        registration.descriptor?.version?.let { runtimeHealthRef?.clear(pluginId, it) }
        if (!store.enable(pluginId)) return false
        val enabled = activateRegistration(manager, registration, runtime)
        if (!enabled) store.disable(pluginId)
        refreshSnapshot(manager); enabled
    }

    fun disablePackage(pluginId: String): Boolean = synchronized(this) {
        val store = storeRef ?: return false
        val disabled = store.disable(pluginId); refreshSnapshot(BuiltInSourcePluginManager.instance); disabled
    }

    fun quarantinePackage(pluginId: String, reason: String = "Quarantined by user"): Boolean = synchronized(this) {
        val store = storeRef ?: return false; val activeVersion = store.readActiveVersion(pluginId)
        val moved = store.quarantineActive(pluginId, reason)
        if (moved) { if (activeVersion != null) runtimeHealthRef?.clear(pluginId, activeVersion); rescanCurrent() }
        moved
    }

    fun restorePackage(pluginId: String): Boolean = synchronized(this) {
        val store = storeRef ?: return false; val restored = store.restoreLatestQuarantined(pluginId)
        if (restored) { store.readActiveVersion(pluginId)?.let { runtimeHealthRef?.clear(pluginId, it) }; rescanCurrent() }
        restored
    }

    fun rollbackPackage(pluginId: String): Boolean = synchronized(this) {
        val installer = installerRef ?: return false; val rolledBack = installer.rollback(pluginId)
        if (rolledBack) { storeRef?.readActiveVersion(pluginId)?.let { runtimeHealthRef?.clear(pluginId, it) }; rescanCurrent() }
        rolledBack
    }

    private fun rescanCurrent(): PluginStoreScanResult {
        val store = storeRef ?: return PluginStoreScanResult(emptyList(), emptyList(), emptyList(), listOf("Plugin store unavailable"))
        val runtime = declarativeRuntimeRef ?: return PluginStoreScanResult(emptyList(), emptyList(), emptyList(), listOf("Plugin runtime unavailable"))
        return rescanAndActivate(BuiltInSourcePluginManager.instance, store, runtime)
    }

    private fun rescanAndActivate(manager: SourcePluginManager, store: PluginPackageStore, runtime: DeclarativePluginRuntime): PluginStoreScanResult {
        val result = store.scanInstalled()
        result.registrations.filter { store.isEnabled(it.packageId) }.forEach { activateRegistration(manager, it, runtime) }
        return result.copy(registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE }).also { lastScanRef = it }
    }

    private fun activateRegistration(manager: SourcePluginManager, registration: SourcePluginRegistration, runtime: DeclarativePluginRuntime): Boolean {
        val manifest = registration.manifest ?: return false; val path = registration.packagePath ?: return false
        if (manifest.runtime != PluginRuntime.DECLARATIVE) return false
        val installedDir = File(path); if (!installedDir.isDirectory) return false
        val packageDir = runtimeCompatiblePackageDir(manifest, installedDir)
        val plugin = runCatching { DeclarativeSourcePlugin(manifest, packageDir, runtime, ::handleRuntimeSuccess, ::handleRuntimeFailure) }.getOrNull() ?: return false
        return manager.enablePackagePlugin(registration.packageId, plugin) != null
    }

    private fun runtimeCompatiblePackageDir(manifest: PluginPackageManifest, installedDir: File): File {
        if (!needsRuntimePatch(manifest)) return installedDir
        val root = pluginRootRef ?: return installedDir; val patched = File(root, "runtime-patches/${manifest.id}-${manifest.version}")
        runCatching {
            if (patched.exists()) patched.deleteRecursively(); installedDir.copyRecursively(patched, overwrite = true)
            when (manifest.id) {
                "izib" -> patchIzibRules(manifest, patched)
                "knigavuhe" -> patchKnigavuheRules(manifest, patched)
                "baza-knig" -> patchBazaKnigRules(manifest, patched)
                "lis10book" -> patchLis10bookRules(manifest, patched)
            }
        }.onFailure { patched.deleteRecursively(); return installedDir }
        return patched.takeIf { it.isDirectory } ?: installedDir
    }

    private fun needsRuntimePatch(manifest: PluginPackageManifest): Boolean = when (manifest.id) {
        "izib" -> manifest.version <= 5
        "knigavuhe" -> manifest.version <= 2
        "baza-knig" -> manifest.version <= 4
        "lis10book" -> manifest.version <= 5
        else -> false
    }

    private fun patchIzibRules(manifest: PluginPackageManifest, packageDir: File) {
        patchJsonRule(manifest, packageDir, "seriesLookup") { root ->
            val series = root.getJSONObject("series"); series.remove("followLink"); series.put("title", "h1, h2, .book-title, .title")
            series.put("titleRegex", "(?i)^(?:серия|серія|серії)\\s*[«\\\"“]?(.+?)[»\\\"”]?\\s*$")
            series.optJSONObject("books")?.apply { put("item", "a[href*='/art']"); put("title", "@text"); put("link", "@href") }
        }
        patchJsonRule(manifest, packageDir, "bookLookup") { root ->
            root.getJSONObject("book").put(
                "author",
                "a[href*='/author']:not([href='/authors']):not([href$='/authors']) || a[href*='/avtor/']"
            )
        }
    }

    private fun patchKnigavuheRules(manifest: PluginPackageManifest, packageDir: File) {
        patchJsonRule(manifest, packageDir, "seriesLookup") { root -> root.getJSONObject("series").apply { put("title", "h1"); put("titleRegex", "(?i)^цикл\\s*[«\\\"“]?(.+?)[»\\\"”]?\\s*(?:-|—|автор\\b|$)"); put("followLink", "a[href*='/serie/']@href") } }
        patchJsonRule(manifest, packageDir, "bookLookup") { root -> root.getJSONObject("book").apply { put("title", "meta[property='og:title']@content"); put("titleRegex", "(?i)^(.+?)(?=\\s*\\(|\\s+-\\s+автор\\b|$)"); put("seriesTitle", "a[href*='/serie/']") } }
    }

    private fun patchBazaKnigRules(manifest: PluginPackageManifest, packageDir: File) {
        patchJsonRule(manifest, packageDir, "seriesLookup") { root ->
            val series = root.getJSONObject("series")
            series.optJSONObject("books")?.apply { put("item", "article, .short, .item"); put("title", "h2 a, h3 a, .title a"); put("link", "h2 a, h3 a, .title a@href") }
            series.optJSONObject("supplement")?.apply { put("maxPages", 5); put("nextPage", "a[rel='next'], .navigation a.next@href"); optJSONObject("items")?.apply { put("item", "article, .short, .item"); put("title", "h2 a, h3 a, .title a"); put("link", "h2 a, h3 a, .title a@href") } }
        }
    }

    private fun patchLis10bookRules(manifest: PluginPackageManifest, packageDir: File) {
        patchJsonRule(manifest, packageDir, "bookLookup") { root ->
            root.getJSONObject("book").put("author", "a[href*='/avtor/'] || a[href*='/author/']")
        }
    }

    private fun patchJsonRule(manifest: PluginPackageManifest, packageDir: File, entrypoint: String, mutate: (JSONObject) -> Unit) {
        val relative = manifest.entrypoints[entrypoint] ?: return; val rule = File(packageDir, relative); if (!rule.isFile) return
        val json = JSONObject(rule.readText()); mutate(json); rule.writeText(json.toString(2))
    }

    private fun handleRuntimeSuccess(pluginId: String, version: Int) { runtimeHealthRef?.recordSuccess(pluginId, version) }
    private fun handleRuntimeFailure(pluginId: String, version: Int, failure: Throwable) = synchronized(this) {
        val health = runtimeHealthRef ?: return@synchronized; val reason = failure.message ?: failure::class.java.simpleName
        val state = runCatching { health.recordFailure(pluginId, version, reason) }.getOrNull() ?: return@synchronized
        if (!health.shouldQuarantine(state)) return@synchronized; val store = storeRef ?: return@synchronized
        if (store.readActiveVersion(pluginId) != version) { health.clear(pluginId, version); return@synchronized }
        val wasEnabled = store.isEnabled(pluginId); val quarantineReason = "Automatic quarantine after ${state.failures} runtime failures: ${state.lastReason}"
        if (!store.quarantineActive(pluginId, quarantineReason)) return@synchronized; health.clear(pluginId, version)
        if (wasEnabled && store.readActiveVersion(pluginId) != null) store.enable(pluginId); rescanCurrent()
    }

    private fun refreshSnapshot(manager: SourcePluginManager) {
        val current = lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        lastScanRef = current.copy(registrations = manager.registrations().filter { it.origin == PluginOrigin.PACKAGE })
    }
}
