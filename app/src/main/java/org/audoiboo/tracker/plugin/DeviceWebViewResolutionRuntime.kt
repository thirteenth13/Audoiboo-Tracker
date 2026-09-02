package org.audoiboo.tracker.plugin

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Host-owned device browser runtime. Site-specific behavior comes from installed plugin mediaCapture rules. */
object DeviceWebViewResolutionRuntime {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    fun supports(url: String): Boolean = captureRegistration(url) != null

    suspend fun resolve(book: SourceBook): List<DownloadCandidate> {
        val registration = captureRegistration(book.url) ?: return emptyList()
        val manifest = registration.manifest ?: return emptyList()
        val packageDir = PluginPackageRuntime.packageDirectory(registration.packageId) ?: return emptyList()
        val relative = manifest.entrypoints["mediaCapture"] ?: return emptyList()
        val rule = runCatching { PluginMediaCaptureRule.load(File(packageDir, relative)) }.getOrNull() ?: return emptyList()
        val result = capture(manifest, rule, book.url) ?: return emptyList()
        return result.mediaUrls.distinct().mapIndexed { index, url ->
            DownloadCandidate(
                type = rule.downloadType,
                url = url,
                fileName = fileName(url),
                quality = "plugin-webview-${manifest.id}",
                priority = 500 - index
            )
        }
    }

    /** Runs the exact host-owned WebView capture used by downloads and preserves runtime diagnostics. */
    suspend fun captureDiagnostics(url: String): PluginMediaCaptureResult? {
        val registration = captureRegistration(url) ?: return null
        val manifest = registration.manifest ?: return null
        val packageDir = PluginPackageRuntime.packageDirectory(registration.packageId) ?: return null
        val relative = manifest.entrypoints["mediaCapture"] ?: return null
        val rule = runCatching { PluginMediaCaptureRule.load(File(packageDir, relative)) }.getOrNull() ?: return null
        return capture(manifest, rule, url)
    }

    private suspend fun capture(
        manifest: PluginPackageManifest,
        rule: PluginMediaCaptureRule,
        url: String
    ): PluginMediaCaptureResult? {
        val context = appContext ?: return null
        if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url)) return null
        return suspendCancellableCoroutine { continuation ->
            val complete: (PluginMediaCaptureResult) -> Unit = { result ->
                if (continuation.isActive) continuation.resume(result)
            }

            when (manifest.id) {
                "baza-knig" -> BazaSequentialMediaCapture(context).capture(url, rule.timeoutMs) { result ->
                    complete(PluginMediaCaptureResult(result.pageUrl, result.mediaUrls, result.diagnostics))
                }
                "izib" -> IzibWebViewMediaCapture(context).capture(url, rule.timeoutMs) { result ->
                    complete(PluginMediaCaptureResult(result.pageUrl, result.mediaUrls, result.diagnostics))
                }
                "knigavuhe" -> KnigavuheWebViewMediaCapture(context).capture(url, rule.timeoutMs) { result ->
                    complete(PluginMediaCaptureResult(result.pageUrl, result.mediaUrls, result.diagnostics))
                }
                "lis10book" -> Lis10BookWebViewMediaCapture(context).capture(url, rule.timeoutMs) { result ->
                    complete(PluginMediaCaptureResult(result.pageUrl, result.mediaUrls, result.diagnostics))
                }
                "poleknig" -> PoleknigWebViewMediaCapture(context).capture(url, rule.timeoutMs) { result ->
                    complete(PluginMediaCaptureResult(result.pageUrl, result.mediaUrls, result.diagnostics))
                }
                else -> PluginWebViewMediaCaptureRuntime(context).capture(manifest, rule, url, complete)
            }
        }
    }

    private fun captureRegistration(url: String): SourcePluginRegistration? =
        PluginPackageRuntime.registrations.firstOrNull { registration ->
            if (registration.origin != PluginOrigin.PACKAGE || registration.state != PluginState.ENABLED) return@firstOrNull false
            val manifest = registration.manifest ?: return@firstOrNull false
            val relative = manifest.entrypoints["mediaCapture"] ?: return@firstOrNull false
            val packageDir = PluginPackageRuntime.packageDirectory(registration.packageId) ?: return@firstOrNull false
            val rule = runCatching { PluginMediaCaptureRule.load(File(packageDir, relative)) }.getOrNull() ?: return@firstOrNull false
            PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url)
        }

    private fun fileName(url: String): String? = runCatching {
        java.net.URI(url).path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }.getOrNull()
}
