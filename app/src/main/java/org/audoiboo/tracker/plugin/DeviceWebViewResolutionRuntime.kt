package org.audoiboo.tracker.plugin

import android.content.Context
import android.media.AudioManager
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
            // Hidden capture WebViews exist only to surface media URLs. Some site players ignore
            // DOM muted/volume hooks or create audio outside the element we hooked. Mute Android's
            // music stream for the short capture window and restore its previous mute state after.
            val audio = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }.getOrNull()
            val wasMuted = runCatching { audio?.isStreamMute(AudioManager.STREAM_MUSIC) == true }.getOrDefault(false)
            if (!wasMuted) runCatching { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0) }

            fun restoreAudio() {
                if (!wasMuted) runCatching { audio?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0) }
            }

            continuation.invokeOnCancellation { restoreAudio() }

            val complete: (PluginMediaCaptureResult) -> Unit = { result ->
                restoreAudio()
                if (continuation.isActive) continuation.resume(result)
            }

            when {
                manifest.id == "baza-knig" -> BazaSequentialMediaCapture(context).capture(url, rule.timeoutMs) { result ->
                    complete(PluginMediaCaptureResult(result.pageUrl, result.mediaUrls, result.diagnostics))
                }
                // The generic declarative runner is the path that actually surfaces Izib media URLs.
                manifest.id == "izib" -> {
                    val effectiveRule = rule.copy(mediaHosts = rule.mediaHosts + "abookfiles.online")
                    PluginWebViewMediaCaptureRuntime(context).capture(manifest, effectiveRule, url, complete)
                }
                // The source browser shows real rows named "Игра Кота_0" ... "Игра Кота_38".
                // The Knigavuhe plugin rule already matches .+_\d+ and toggles "Большие отрезки";
                // use that declarative runner instead of the ported zero-label traversal.
                manifest.id == "knigavuhe" ->
                    PluginWebViewMediaCaptureRuntime(context).capture(manifest, rule, url, complete)
                // Poleknig's custom player only requests /files/<id> after a real user-style play
                // gesture. The dedicated runtime taps 01..N and the large square play control with
                // native MotionEvents, instead of relying on synthetic DOM click().
                manifest.id == "poleknig" ->
                    PoleknigPlayerCapture(context).capture(manifest, rule, url, complete)
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
