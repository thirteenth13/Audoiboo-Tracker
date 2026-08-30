package org.audoiboo.tracker.plugin

import java.io.File

/** Host-owned runtime state for package discovery. No external plugin code is executed here. */
object PluginPackageRuntime {
    @Volatile private var initialized = false
    @Volatile private var storeRef: PluginPackageStore? = null
    @Volatile private var lastScanRef: PluginStoreScanResult? = null

    val store: PluginPackageStore?
        get() = storeRef

    val lastScan: PluginStoreScanResult?
        get() = lastScanRef

    fun initialize(appFilesDir: File): PluginStoreScanResult = synchronized(this) {
        if (initialized) return lastScanRef ?: PluginStoreScanResult(emptyList(), emptyList(), emptyList(), emptyList())
        val root = File(appFilesDir, "plugins")
        val store = PluginPackageStore(root, BuiltInSourcePluginManager.instance)
        val result = store.scanInstalled()
        storeRef = store
        lastScanRef = result
        initialized = true
        result
    }
}
