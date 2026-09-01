package org.audoiboo.tracker.plugin

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Host-owned device browser runtime. Site-specific behavior comes from installed plugin mediaCapture rules. */
object DeviceWebViewResolutionRuntime {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun supports(url: String): Boolean = captureRegistration(url) != null

    suspend fun resolve(book: SourceBook): List<DownloadCandidate> {
        val context = appContext ?: return emptyList()
        val registration = captureRegistration(book.url) ?: return emptyList()
        val manifest = registration.manifest ?: return emptyList()
        val packageDir = registration.packagePath?.let(::File) ?: return emptyList()
        val relative = manifest.entrypoints["mediaCapture"] ?: return emptyList()
        val ruleFile = File(packageDir, relative)
        val rule = runCatching { PluginMediaCaptureRule.load(ruleFile) }.getOrNull() ?: return emptyList()
        if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, book.url)) return emptyList()

        return suspendCancellableCoroutine { continuation ->
            PluginWebViewMediaCaptureRuntime(context).capture(manifest, rule, book.url) { result ->
                if (!continuation.isActive) return@capture
                continuation.resume(result.mediaUrls.distinct().mapIndexed { index, url ->
                    DownloadCandidate(
                        type = rule.downloadType,
                        url = url,
                        fileName = fileName(url),
                        quality = "plugin-webview-${manifest.id}",
                        priority = 500 - index
                    )
                })
            }
        }
    }

    private fun captureRegistration(url: String): SourcePluginRegistration? =
        PluginPackageRuntime.registrations.firstOrNull { registration ->
            if (registration.origin != PluginOrigin.PACKAGE || registration.state != PluginState.ENABLED) return@firstOrNull false
            val manifest = registration.manifest ?: return@firstOrNull false
            if ("mediaCapture" !in manifest.entrypoints) return@firstOrNull false
            val packageDir = registration.packagePath?.let(::File) ?: return@firstOrNull false
            val rule = runCatching { PluginMediaCaptureRule.load(File(packageDir, manifest.entrypoints.getValue("mediaCapture"))) }.getOrNull()
                ?: return@firstOrNull false
            PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url)
        }

    private fun fileName(url: String): String? = runCatching {
        java.net.URI(url).path.substringAfterLast('/').takeIf { it.isNotBlank() }
    }.getOrNull()
}
