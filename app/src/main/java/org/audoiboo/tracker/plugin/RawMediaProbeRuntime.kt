package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class RawMediaProbeResult(
    val candidates: List<String>,
    val diagnostics: List<String>
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
                val found = LinkedHashSet<String>()
                val events = mutableListOf<String>()
                val finished = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                val webView = WebView(context)

                fun remember(raw: String?) {
                    val normalized = normalize(raw, url) ?: return
                    if (!looksMediaLike(normalized)) return
                    if (found.size < 40) found += normalized
                }

                fun finish(reason: String) {
                    if (!finished.compareAndSet(false, true)) return
                    events += reason
                    events += "rawCandidates=${found.size}"
                    handler.removeCallbacksAndMessages(null)
                    runCatching {
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.removeJavascriptInterface(BRIDGE)
                        webView.destroy()
                    }
                    if (continuation.isActive) continuation.resume(RawMediaProbeResult(found.toList(), events.toList()))
                }

                @SuppressLint("SetJavaScriptEnabled")
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = userAgentString.replace("; wv", "")
                }
                webView.addJavascriptInterface(object {
                    @JavascriptInterface fun candidate(value: String?) = handler.post { remember(value) }
                    @JavascriptInterface fun event(value: String?) = handler.post {
                        if (!value.isNullOrBlank() && events.size < 30) events += "js:$value"
                    }
                }, BRIDGE)
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        remember(request?.url?.toString())
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, pageUrl)) return
                        events += "loaded"
                        view.evaluateJavascript(script(rule), null)
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(scanScript(rule), null) }, 1200L)
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(scanScript(rule), null) }, 3200L)
                    }
                }
                handler.postDelayed({ finish("probe-timeout") }, 6500L)
                webView.loadUrl(url)
            }
        }
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

    private fun script(rule: PluginMediaCaptureRule): String = """
        (() => {
          if(window.__audoibooRawProbe) return;
          window.__audoibooRawProbe=true;
          const emit=u=>{try{if(u)AudoibooRawProbe.candidate(new URL(String(u),location.href).href)}catch(_){}};
          const scanText=t=>{try{const v=String(t||'').replaceAll('\\/','/');const a=v.match(/(?:https?:\\/\\/|\\/\\/|\\/)[^\"'<>\\s]+(?:\\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\\/files\\/\\d+)(?:\\?[^\"'<>\\s]*)?/gi)||[];a.slice(0,500).forEach(emit);return a.length}catch(_){return 0}};
          window.__audoibooRawEmit=emit;window.__audoibooRawScan=scanText;
          try{const s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s&&s.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);return s.set.call(this,v)}})}catch(_){}
          try{const f=window.fetch;if(f)window.fetch=function(...a){try{a.forEach(scanText)}catch(_){};return f.apply(this,a).then(r=>{try{r.clone().text().then(scanText).catch(()=>{})}catch(_){};return r;})}}catch(_){}
          try{const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(...a){try{a.forEach(scanText)}catch(_){};this.addEventListener('load',()=>{try{scanText(this.responseText)}catch(_){}});return o.apply(this,a)}}catch(_){}
          return true;
        })();
    """.trimIndent()

    private fun scanScript(rule: PluginMediaCaptureRule): String = """
        (() => {
          const emit=window.__audoibooRawEmit||(()=>{}),scanText=window.__audoibooRawScan||(()=>0),norm=s=>String(s||'').replace(/\\s+/g,' ').trim();
          document.querySelectorAll('audio[src],audio source[src],source[src],a[href]').forEach(e=>emit(e.src||e.href));
          try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){}
          scanText(document.documentElement.innerHTML);
          const sel=${js(rule.activateSelector ?: "button,a,div,span,li,label")};
          const pat=${js(rule.activateLabelRegex?.pattern ?: "(?:слушать|воспроизвести|play|▶)|^\\d{1,3}(?:[\\s._:)-]+.*)?$")};
          try{const re=new RegExp(pat,'i');const c=[...document.querySelectorAll(sel)].filter(e=>{const t=norm(e.innerText||e.textContent);return t&&t.length<=180&&re.test(t)}).slice(0,40);AudoibooRawProbe.event('activate='+c.length);c.forEach((e,i)=>setTimeout(()=>{try{e.click()}catch(_){}},i*120))}catch(_){}
          return true;
        })();
    """.trimIndent()

    private const val BRIDGE = "AudoibooRawProbe"
    private val MEDIA_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "m3u8")
}
