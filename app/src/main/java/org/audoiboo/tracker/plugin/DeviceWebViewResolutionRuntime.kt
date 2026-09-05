package org.audoiboo.tracker.plugin

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Host-owned device browser runtime. Plugins provide only declarative site configuration. */
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

        // These sites publish a complete player playlist/config. Prefer that deterministic source;
        // the existing WebView/sequential engines below remain compatibility fallbacks.
        val direct = withContext(Dispatchers.IO) { DirectSiteMediaResolver.resolve(manifest, url) }
        if (direct != null) {
            return PluginMediaCaptureResult(url, direct.mediaUrls, direct.diagnostics + "direct-playlist")
        }

        return suspendCancellableCoroutine { continuation ->
            val audio = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()
            val wasMuted = runCatching { audio?.isStreamMute(AudioManager.STREAM_MUSIC) == true }.getOrDefault(false)
            if (!wasMuted) runCatching { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }

            fun restoreAudio() {
                if (!wasMuted) runCatching { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
            }

            continuation.invokeOnCancellation { restoreAudio() }
            val complete: (PluginMediaCaptureResult) -> Unit = { result ->
                restoreAudio()
                if (continuation.isActive) continuation.resume(
                    result.copy(diagnostics = result.diagnostics + "direct-playlist-fallback")
                )
            }

            when {
                manifest.id == "baza-knig" -> BazaSequentialMediaCapture(context).capture(url, rule.timeoutMs) { result ->
                    complete(PluginMediaCaptureResult(result.pageUrl, result.mediaUrls, result.diagnostics))
                }
                manifest.id == "izib" -> {
                    // v5+ packages declare abookfiles.online themselves. Keep only a compatibility
                    // shim for already-installed v4 packages until the package updater replaces them.
                    val effectiveRule = if (manifest.version < 5) {
                        rule.copy(mediaHosts = rule.mediaHosts + "abookfiles.online")
                    } else rule
                    PluginWebViewMediaCaptureRuntime(context).capture(manifest, effectiveRule, url, complete)
                }
                manifest.id == "lis10book" -> {
                    // v5+ packages declare fantbox.net themselves; v4 gets a temporary compatibility shim.
                    val effectiveRule = if (manifest.version < 5) {
                        rule.copy(mediaHosts = rule.mediaHosts + "fantbox.net")
                    } else rule
                    PluginWebViewMediaCaptureRuntime(context).capture(manifest, effectiveRule, url, complete)
                }
                manifest.id == "knigavuhe" -> KnigavuhePlayerCapture(context).capture(manifest, rule, url, complete)
                manifest.id == "poleknig" -> PoleknigPlayerCapture(context).capture(manifest, rule, url, complete)
                PortedExperimentalMediaRuntime.supports(manifest.id) ->
                    PortedExperimentalMediaRuntime.capture(context, manifest, rule, url, complete)
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
