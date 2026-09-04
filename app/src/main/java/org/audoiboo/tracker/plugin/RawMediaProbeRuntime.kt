package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class RawMediaCandidate(
    val url: String,
    val sources: List<String>,
    val host: String,
    val extension: String?,
    val accepted: Boolean,
    val filter: String
)

data class RawMediaProbeResult(
    val candidates: List<RawMediaCandidate>,
    val diagnostics: List<String>,
    val elapsedMs: Long,
    val loadedMs: Long?,
    val firstCandidateMs: Long?,
    val activateCount: Int?
)

/** Diagnostic-only WebView probe that records audio-looking URLs before plugin media filtering. */
object RawMediaProbeRuntime {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) { appContext = context.applicationContext }

    suspend fun probe(url: String): RawMediaProbeResult? {
        val context = appContext ?: return null
        val registration = PluginPackageRuntime.registrations.firstOrNull { registration ->
            if (registration.origin != PluginOrigin.PACKAGE || registration.state != PluginState.ENABLED) return@firstOrNull false
            val manifest = registration.manifest ?: return@firstOrNull false
            val relative = manifest.entrypoints["mediaCapture"] ?: return@firstOrNull false
            val packageDir = PluginPackageRuntime.packageDirectory(registration.packageId) ?: return@firstOrNull false
            val rule = runCatching { PluginMediaCaptureRule.load(File(packageDir, relative)) }.getOrNull() ?: return@firstOrNull false
            PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url)
        } ?: return null
        val manifest = registration.manifest ?: return null
        val packageDir = PluginPackageRuntime.packageDirectory(registration.packageId) ?: return null
        val relative = manifest.entrypoints["mediaCapture"] ?: return null
        val rule = runCatching { PluginMediaCaptureRule.load(File(packageDir, relative)) }.getOrNull() ?: return null

        return suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val started = SystemClock.elapsedRealtime()
                val found = LinkedHashMap<String, LinkedHashSet<String>>()
                val events = mutableListOf<String>()
                val finished = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                val webView = WebView(context)
                var loadedMs: Long? = null
                var firstCandidateMs: Long? = null
                var activateCount: Int? = null

                fun remember(raw: String?, source: String) {
                    val normalized = normalize(raw, url) ?: return
                    if (!looksMediaLike(normalized)) return
                    if (firstCandidateMs == null) firstCandidateMs = SystemClock.elapsedRealtime() - started
                    if (found.size < 80 || found.containsKey(normalized)) found.getOrPut(normalized) { LinkedHashSet() } += source
                }

                fun finish(reason: String) {
                    if (!finished.compareAndSet(false, true)) return
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val candidates = found.map { (candidateUrl, sources) -> classify(manifest, rule, candidateUrl, sources.toList()) }
                    events += reason
                    events += "raw=${candidates.size} accepted=${candidates.count { it.accepted }} filtered=${candidates.count { !it.accepted }}"
                    events += "timing:loaded=${loadedMs ?: -1}ms firstCandidate=${firstCandidateMs ?: -1}ms total=${elapsed}ms"
                    handler.removeCallbacksAndMessages(null)
                    runCatching {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.removeJavascriptInterface(BRIDGE)
                        webView.destroy()
                    }
                    if (continuation.isActive) continuation.resume(
                        RawMediaProbeResult(candidates, events.toList(), elapsed, loadedMs, firstCandidateMs, activateCount)
                    )
                }

                @SuppressLint("SetJavaScriptEnabled")
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = userAgentString.replace("; wv", "")
                }
                webView.addJavascriptInterface(object {
                    @JavascriptInterface fun candidate(value: String?, source: String?) = handler.post { remember(value, source ?: "js") }
                    @JavascriptInterface fun event(value: String?) = handler.post {
                        if (value.isNullOrBlank()) return@post
                        if (events.size < 50) events += "js:$value"
                        Regex("^activate=(\\d+)$").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { activateCount = it }
                    }
                }, BRIDGE)
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        remember(request?.url?.toString(), "network")
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, pageUrl)) return
                        if (loadedMs == null) loadedMs = SystemClock.elapsedRealtime() - started
                        events += "loaded:${runCatching { URI(pageUrl).host }.getOrNull() ?: "?"}"
                        view.evaluateJavascript(script(), null)
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(scanScript(rule), null) }, 700L)
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(scanScript(rule), null) }, 2200L)
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(scanScript(rule), null) }, 4200L)
                    }
                }
                handler.postDelayed({ finish("probe-timeout") }, 7000L)
                webView.loadUrl(url)
            }
        }
    }

    private fun classify(manifest: PluginPackageManifest, rule: PluginMediaCaptureRule, url: String, sources: List<String>): RawMediaCandidate {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host?.lowercase().orEmpty()
        val path = uri?.path.orEmpty().lowercase()
        val ext = path.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val resolver = rule.resolverPathRegex?.matches(uri?.path.orEmpty()) == true && manifest.hosts.any { host == it || host.endsWith(".$it") }
        if (resolver) return RawMediaCandidate(url, sources, host, ext, true, "resolver")
        val allowedHosts = if (rule.mediaHosts.isEmpty()) manifest.permissions.effectiveDownloadHosts else rule.mediaHosts
        val trustedHost = when (manifest.id) {
            "izib" -> host == "abookfiles.online" || host.endsWith(".abookfiles.online")
            "lis10book" -> host == "fantbox.net" || host.endsWith(".fantbox.net")
            else -> false
        }
        val hostOk = trustedHost || allowedHosts.any { host == it || host.endsWith(".$it") }
        val extOk = rule.mediaExtensions.isEmpty() || ext in rule.mediaExtensions
        val filter = when {
            uri?.scheme?.lowercase() !in setOf("http", "https") -> "rejected:scheme"
            !hostOk -> "rejected:host"
            !extOk -> "rejected:extension"
            rule.mediaPathContains?.let(path::contains) == false -> "rejected:pathContains"
            rule.mediaPathRegex?.containsMatchIn(path) == false -> "rejected:pathRegex"
            else -> "accepted:media"
        }
        return RawMediaCandidate(url, sources, host, ext, filter.startsWith("accepted"), filter)
    }

    private fun looksMediaLike(url: String): Boolean = runCatching {
        val uri = URI(url)
        val path = uri.path.orEmpty().lowercase()
        path.substringAfterLast('.', "") in MEDIA_EXTENSIONS || Regex("/files/\\d+/?$").containsMatchIn(path)
    }.getOrDefault(false)

    private fun normalize(raw: String?, base: String): String? = runCatching {
        val value = raw?.trim()?.replace("\\/", "/").orEmpty()
        if (value.isBlank()) return@runCatching null
        URI(base).resolve(value).toString()
    }.getOrNull()

    private fun js(value: String?): String = JSONObject.quote(value.orEmpty())

    private fun script(): String = """
        (() => {
          if(window.__audoibooRawProbe) return;
          window.__audoibooRawProbe=true;
          const emit=(u,s)=>{try{if(u)AudoibooRawProbe.candidate(new URL(String(u),location.href).href,s||'js')}catch(_){}};
          const scanText=(t,s)=>{try{const v=String(t||'').replaceAll('\\/','/');const a=v.match(/(?:https?:\\/\\/|\\/\\/|\\/)[^\"'<>\\s]+(?:\\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\\/files\\/\\d+)(?:\\?[^\"'<>\\s]*)?/gi)||[];a.slice(0,700).forEach(x=>emit(x,s));return a.length}catch(_){return 0}};
          window.__audoibooRawEmit=emit;window.__audoibooRawScan=scanText;
          try{
            const p=HTMLMediaElement.prototype.play;
            HTMLMediaElement.prototype.play=function(){try{this.muted=true;this.volume=0}catch(_){};return p.apply(this,arguments)};
            const md=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'muted');
            if(md&&md.set)Object.defineProperty(HTMLMediaElement.prototype,'muted',{configurable:true,enumerable:md.enumerable,get(){return true},set(_){try{md.set.call(this,true)}catch(_){}}});
            const vd=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'volume');
            if(vd&&vd.set)Object.defineProperty(HTMLMediaElement.prototype,'volume',{configurable:true,enumerable:vd.enumerable,get(){return 0},set(_){try{vd.set.call(this,0)}catch(_){}}});
            document.querySelectorAll('audio,video').forEach(x=>{try{x.muted=true;x.volume=0}catch(_){}});
            setInterval(()=>document.querySelectorAll('audio,video').forEach(x=>{try{x.muted=true;x.volume=0}catch(_){}}),100);
          }catch(_){}
          try{const A=window.Audio;if(A){window.Audio=function(src){const a=new A(src);try{a.muted=true;a.volume=0}catch(_){};emit(src,'audio-constructor');return a};window.Audio.prototype=A.prototype;}}catch(_){}
          try{const s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s&&s.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v,'media-src');try{this.muted=true;this.volume=0}catch(_){};return s.set.call(this,v)}})}catch(_){}
          try{const f=window.fetch;if(f)window.fetch=function(...a){try{a.forEach(x=>scanText(x,'fetch-request'))}catch(_){};return f.apply(this,a).then(r=>{try{r.clone().text().then(x=>scanText(x,'fetch-response')).catch(()=>{})}catch(_){};return r;})}}catch(_){}
          try{const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(...a){try{a.forEach(x=>scanText(x,'xhr-request'))}catch(_){};this.addEventListener('load',()=>{try{scanText(this.responseText,'xhr-response')}catch(_){}});return o.apply(this,a)}}catch(_){}
          return true;
        })();
    """.trimIndent()

    private fun scanScript(rule: PluginMediaCaptureRule): String = """
        (() => {
          const emit=window.__audoibooRawEmit||(()=>{}),scanText=window.__audoibooRawScan||(()=>0),norm=s=>String(s||'').replace(/\\s+/g,' ').trim();
          document.querySelectorAll('audio,video,source').forEach(e=>{try{if('muted' in e)e.muted=true;if('volume' in e)e.volume=0}catch(_){};emit(e.src,'dom-media')});
          document.querySelectorAll('a[href]').forEach(e=>emit(e.href,'link'));
          try{performance.getEntriesByType('resource').forEach(e=>emit(e.name,'performance'))}catch(_){}
          scanText(document.documentElement.innerHTML,'html');
          const sel=${js(rule.activateSelector ?: "button,a,div,span,li,label")};
          const pat=${js(rule.activateLabelRegex?.pattern ?: "(?:слушать|воспроизвести|play|▶)|^\\d{1,3}(?:[\\s._:)-]+.*)?$")};
          try{const re=new RegExp(pat,'i');const c=[...document.querySelectorAll(sel)].filter(e=>{const t=norm(e.innerText||e.textContent);return t&&t.length<=180&&re.test(t)}).slice(0,40);AudoibooRawProbe.event('activate='+c.length);c.slice(0,6).forEach(e=>AudoibooRawProbe.event('activate-label:'+norm(e.innerText||e.textContent).slice(0,80)));c.forEach((e,i)=>setTimeout(()=>{try{document.querySelectorAll('audio,video').forEach(x=>{x.muted=true;x.volume=0});e.click()}catch(_){}},i*120))}catch(_){}
          return true;
        })();
    """.trimIndent()

    private const val BRIDGE = "AudoibooRawProbe"
    private val MEDIA_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "m3u8")
}
