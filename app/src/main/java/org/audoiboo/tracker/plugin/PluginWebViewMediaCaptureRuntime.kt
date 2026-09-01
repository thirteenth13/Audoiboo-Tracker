package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

data class PluginMediaCaptureResult(
    val pageUrl: String,
    val mediaUrls: List<String>,
    val diagnostics: List<String>
)

data class PluginMediaCaptureRule(
    val pagePathRegex: Regex?,
    val mediaExtensions: Set<String>,
    val mediaHosts: Set<String>,
    val mediaPathContains: String?,
    val mediaPathRegex: Regex?,
    val resolverPathRegex: Regex?,
    val requireResolverBeforeExternalMedia: Boolean,
    val preferDirectMedia: Boolean,
    val maxResults: Int,
    val timeoutMs: Long,
    val armDelayMs: Long,
    val finishDelayMs: Long,
    val scanIntervalsMs: List<Long>,
    val scanDomMedia: Boolean,
    val scanLinks: Boolean,
    val scanScripts: Boolean,
    val scanDataAttributes: Boolean,
    val scanPerformance: Boolean,
    val scanHtml: Boolean,
    val scanPlayerGlobals: Boolean,
    val hookFetch: Boolean,
    val hookXhr: Boolean,
    val hookMediaSrc: Boolean,
    val hookAudioConstructor: Boolean,
    val activateSelector: String?,
    val activateLabelRegex: Regex?,
    val activateIntervalMs: Long,
    val activateMax: Int,
    val prepareClickRegex: Regex?,
    val prepareToggleRegex: Regex?,
    val prepareDelayMs: Long,
    val sortTrackNumber: Boolean,
    val downloadType: DownloadType
) {
    companion object {
        fun load(file: File): PluginMediaCaptureRule {
            val root = JSONObject(file.readText()).optJSONObject("media") ?: JSONObject(file.readText())
            fun strings(name: String): Set<String> = root.optJSONArray(name)?.let { a ->
                (0 until a.length()).mapTo(linkedSetOf()) { a.getString(it).lowercase() }
            } ?: emptySet()
            fun longs(name: String): List<Long> = root.optJSONArray(name)?.let { a ->
                (0 until a.length()).map { a.getLong(it) }
            } ?: emptyList()
            fun regex(name: String): Regex? = root.optString(name).takeIf { it.isNotBlank() }?.let { Regex(it) }
            return PluginMediaCaptureRule(
                pagePathRegex = regex("pagePathRegex"),
                mediaExtensions = strings("mediaExtensions"),
                mediaHosts = strings("mediaHosts"),
                mediaPathContains = root.optString("mediaPathContains").takeIf { it.isNotBlank() },
                mediaPathRegex = regex("mediaPathRegex"),
                resolverPathRegex = regex("resolverPathRegex"),
                requireResolverBeforeExternalMedia = root.optBoolean("requireResolverBeforeExternalMedia", false),
                preferDirectMedia = root.optBoolean("preferDirectMedia", true),
                maxResults = root.optInt("maxResults", 250).coerceIn(1, 500),
                timeoutMs = root.optLong("timeoutMs", 24_000L).coerceIn(3_000L, 60_000L),
                armDelayMs = root.optLong("armDelayMs", 0L).coerceAtLeast(0L),
                finishDelayMs = root.optLong("finishDelayMs", 18_000L).coerceAtLeast(1_000L),
                scanIntervalsMs = longs("scanIntervalsMs").ifEmpty { listOf(800L, 3_000L, 7_000L, 12_000L) },
                scanDomMedia = root.optBoolean("scanDomMedia", true),
                scanLinks = root.optBoolean("scanLinks", false),
                scanScripts = root.optBoolean("scanScripts", false),
                scanDataAttributes = root.optBoolean("scanDataAttributes", false),
                scanPerformance = root.optBoolean("scanPerformance", false),
                scanHtml = root.optBoolean("scanHtml", false),
                scanPlayerGlobals = root.optBoolean("scanPlayerGlobals", false),
                hookFetch = root.optBoolean("hookFetch", true),
                hookXhr = root.optBoolean("hookXhr", true),
                hookMediaSrc = root.optBoolean("hookMediaSrc", true),
                hookAudioConstructor = root.optBoolean("hookAudioConstructor", true),
                activateSelector = root.optString("activateSelector").takeIf { it.isNotBlank() },
                activateLabelRegex = regex("activateLabelRegex"),
                activateIntervalMs = root.optLong("activateIntervalMs", 180L).coerceIn(50L, 2_000L),
                activateMax = root.optInt("activateMax", 100).coerceIn(0, 300),
                prepareClickRegex = regex("prepareClickRegex"),
                prepareToggleRegex = regex("prepareToggleRegex"),
                prepareDelayMs = root.optLong("prepareDelayMs", 500L).coerceAtLeast(0L),
                sortTrackNumber = root.optBoolean("sortTrackNumber", true),
                downloadType = root.optString("downloadType", DownloadType.STREAM.name).let(DownloadType::valueOf)
            )
        }
    }
}

/** Host-owned WebView engine. Plugins provide data-only capture rules; no plugin JavaScript is executed. */
class PluginWebViewMediaCaptureRuntime(private val context: Context) {
    fun capture(
        manifest: PluginPackageManifest,
        rule: PluginMediaCaptureRule,
        pageUrl: String,
        onComplete: (PluginMediaCaptureResult) -> Unit
    ) {
        require(isAllowedPage(manifest, rule, pageUrl)) { "Unsupported plugin media-capture URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedSet(LinkedHashSet<String>())
            val foundKeys = Collections.synchronizedSet(LinkedHashSet<String>())
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val armed = AtomicBoolean(rule.armDelayMs == 0L)
            val resolverSeen = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                if (!armed.get()) return
                val url = normalizeUrl(raw, pageUrl) ?: return
                if (isResolver(manifest, rule, url)) {
                    resolverSeen.set(true)
                    synchronized(found) { if (found.size < rule.maxResults) found += url }
                    return
                }
                if (!isMedia(manifest, rule, url)) return
                if (rule.requireResolverBeforeExternalMedia && !resolverSeen.get()) return
                val key = mediaKey(url) ?: return
                synchronized(found) {
                    if (found.size >= rule.maxResults || !foundKeys.add(key)) return
                    found += url
                }
            }

            fun snapshot(): List<String> = synchronized(found) {
                val all = found.toList()
                val direct = all.filter { isMedia(manifest, rule, it) }
                val selected = if (rule.preferDirectMedia && direct.isNotEmpty()) direct else all
                if (rule.sortTrackNumber) selected.sortedWith(compareBy({ trackNumber(it) ?: Int.MAX_VALUE }, { it })) else selected
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                val media = snapshot()
                diagnostics += reason
                diagnostics += "media=${media.size}"
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                onComplete(PluginMediaCaptureResult(pageUrl, media, diagnostics.toList()))
            }

            @SuppressLint("SetJavaScriptEnabled")
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = userAgentString.replace("; wv", "")
            }
            webView.addJavascriptInterface(object {
                @JavascriptInterface fun media(url: String?) = handler.post { remember(url) }
                @JavascriptInterface fun event(message: String?) = handler.post {
                    if (!message.isNullOrBlank() && diagnostics.size < 100) diagnostics += "js:$message"
                }
            }, BRIDGE)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!isAllowedPage(manifest, rule, url)) return
                    diagnostics += "loaded"
                    view.evaluateJavascript(buildInstallHooks(rule), null)
                    if (rule.prepareClickRegex != null || rule.prepareToggleRegex != null) {
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(buildPrepare(rule), null) }, rule.prepareDelayMs)
                    }
                    if (rule.armDelayMs > 0) {
                        handler.postDelayed({
                            if (!finished.get()) {
                                synchronized(found) { found.clear(); foundKeys.clear() }
                                armed.set(true)
                                diagnostics += "capture-armed"
                                view.evaluateJavascript(buildScan(rule), null)
                            }
                        }, rule.armDelayMs)
                    }
                    rule.scanIntervalsMs.forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(buildScan(rule), null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("captured") }, rule.finishDelayMs)
                }
            }
            handler.postDelayed({ finish("timeout") }, rule.timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooPluginCapture"

        fun isAllowedPage(manifest: PluginPackageManifest, rule: PluginMediaCaptureRule, url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme?.lowercase() in setOf("http", "https") &&
                manifest.hosts.any { host == it || host.endsWith(".$it") } &&
                (rule.pagePathRegex?.containsMatchIn(uri.path.orEmpty()) != false)
        }.getOrDefault(false)

        fun isMedia(manifest: PluginPackageManifest, rule: PluginMediaCaptureRule, url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path.orEmpty().lowercase()
            val extOk = rule.mediaExtensions.isEmpty() || path.substringAfterLast('.', "") in rule.mediaExtensions
            val hostOk = if (rule.mediaHosts.isEmpty()) {
                manifest.permissions.effectiveDownloadHosts.any { host == it || host.endsWith(".$it") }
            } else rule.mediaHosts.any { host == it || host.endsWith(".$it") }
            uri.scheme?.lowercase() in setOf("http", "https") && hostOk && extOk &&
                (rule.mediaPathContains?.let(path::contains) != false) &&
                (rule.mediaPathRegex?.containsMatchIn(path) != false)
        }.getOrDefault(false)

        private fun isResolver(manifest: PluginPackageManifest, rule: PluginMediaCaptureRule, url: String): Boolean = runCatching {
            val regex = rule.resolverPathRegex ?: return@runCatching false
            val uri = URI(url.trim())
            val host = uri.host?.lowercase().orEmpty()
            manifest.hosts.any { host == it || host.endsWith(".$it") } && regex.matches(uri.path.orEmpty())
        }.getOrDefault(false)

        fun mediaKey(url: String): String? = runCatching {
            val uri = URI(url.trim())
            "${uri.scheme?.lowercase()}://${uri.host?.lowercase()}${uri.path}"
        }.getOrNull()

        fun trackNumber(url: String): Int? = runCatching {
            val name = URI(url).path.substringAfterLast('/').substringBeforeLast('.')
            Regex("(?<!\\d)(\\d{1,4})(?!\\d)").findAll(name)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }.lastOrNull()
        }.getOrNull()

        private fun normalizeUrl(raw: String?, base: String): String? = runCatching {
            val value = raw?.trim()?.replace("\\/", "/").orEmpty()
            if (value.isBlank()) return@runCatching null
            URI(base).resolve(value).toString()
        }.getOrNull()

        private fun js(value: String?): String = JSONObject.quote(value.orEmpty())

        private fun buildInstallHooks(rule: PluginMediaCaptureRule): String = """
            (() => {
              if (window.__audoibooPluginHooks) return 'already';
              window.__audoibooPluginHooks = true;
              const emit = u => { try { if (u) AudoibooPluginCapture.media(new URL(String(u), location.href).href); } catch (_) {} };
              const scanText = text => {
                try {
                  const v=String(text||'').replaceAll('\\/','/');
                  const urls=v.match(/(?:https?:\\/\\/|\\/\\/|\\/)[^\"'<>\\s]+(?:\\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\\/files\\/\\d+)(?:\\?[^\"'<>\\s]*)?/gi)||[];
                  urls.slice(0,700).forEach(emit); return urls.length;
                } catch (_) { return 0; }
              };
              window.__audoibooPluginEmit=emit; window.__audoibooPluginScanText=scanText;
              ${if (rule.hookFetch) "const f=window.fetch;if(f)window.fetch=function(...a){try{a.forEach(scanText)}catch(_){};return f.apply(this,a).then(r=>{try{r.clone().text().then(scanText).catch(()=>{})}catch(_){};return r;});};" else ""}
              ${if (rule.hookXhr) "const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(...a){try{a.forEach(scanText)}catch(_){};this.addEventListener('load',()=>{try{if(typeof this.responseText==='string')scanText(this.responseText)}catch(_){}});return o.apply(this,a);};" else ""}
              ${if (rule.hookMediaSrc) "const s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s&&s.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);return s.set.call(this,v);}});" else ""}
              ${if (rule.hookAudioConstructor) "const A=window.Audio;window.Audio=function(src){const a=new A(src);emit(src);return a;};window.Audio.prototype=A.prototype;" else ""}
              return 'installed';
            })();
        """.trimIndent()

        private fun buildPrepare(rule: PluginMediaCaptureRule): String = """
            (() => {
              const norm=s=>String(s||'').replace(/\\s+/g,' ').trim();
              const nodes=[...document.querySelectorAll('button,a,div,span,li,label,input')];
              const clickRe=${if (rule.prepareClickRegex != null) "new RegExp(${js(rule.prepareClickRegex.pattern)},'i')" else "null"};
              const toggleRe=${if (rule.prepareToggleRegex != null) "new RegExp(${js(rule.prepareToggleRegex.pattern)},'i')" else "null"};
              if(clickRe){const e=nodes.find(x=>clickRe.test(norm(x.innerText||x.value||x.textContent)));if(e)try{e.click();}catch(_){}}
              if(toggleRe){const label=nodes.find(x=>toggleRe.test(norm(x.innerText||x.value||x.textContent)));const cb=label&&(label.matches&&label.matches('input[type=checkbox]')?label:label.querySelector&&label.querySelector('input[type=checkbox]'));if(cb&&!cb.checked)try{cb.click();cb.dispatchEvent(new Event('change',{bubbles:true}));}catch(_){}}
              return true;
            })();
        """.trimIndent()

        private fun buildScan(rule: PluginMediaCaptureRule): String = """
            (() => {
              const emit=window.__audoibooPluginEmit||(()=>{}), scanText=window.__audoibooPluginScanText||(()=>0);
              const norm=s=>String(s||'').replace(/\\s+/g,' ').trim();
              ${if (rule.scanDomMedia) "document.querySelectorAll('audio[src],audio source[src],source[src]').forEach(e=>emit(e.src));" else ""}
              ${if (rule.scanLinks) "document.querySelectorAll('a[href]').forEach(e=>emit(e.href));" else ""}
              ${if (rule.scanScripts) "document.querySelectorAll('script').forEach(s=>scanText(s.textContent||s.innerHTML));" else ""}
              ${if (rule.scanDataAttributes) "document.querySelectorAll('*').forEach(e=>Array.from(e.attributes||[]).forEach(a=>{if(/^(src|href|data-|onclick|onplay)/i.test(a.name))scanText(a.value);}));" else ""}
              ${if (rule.scanPerformance) "try{performance.getEntriesByType('resource').forEach(e=>emit(e.name));}catch(_){}" else ""}
              ${if (rule.scanHtml) "scanText(document.documentElement.innerHTML);" else ""}
              ${if (rule.scanPlayerGlobals) "try{const seen=new WeakSet();let n=0;const walk=(v,d)=>{if(d>5||n>5000||v==null)return;if(typeof v==='string'){scanText(v);return;}if(typeof v!=='object'&&typeof v!=='function')return;try{if(seen.has(v))return;seen.add(v);n++;Object.keys(v).slice(0,400).forEach(k=>{let c;try{c=v[k]}catch(_){return;}if(typeof c==='string')scanText(c);else if(Array.isArray(c)||/track|play|audio|list|data|book|file|src|url|sound|media/i.test(k))walk(c,d+1);});}catch(_){}};Object.keys(window).filter(k=>/track|playlist|audio|player|book|sound|media|data|file/i.test(k)).slice(0,180).forEach(k=>{try{walk(window[k],0)}catch(_){}});}catch(_){}" else ""}
              ${if (rule.activateSelector != null && rule.activateLabelRegex != null && rule.activateMax > 0) "if(!window.__audoibooPluginRunner){const re=new RegExp(${js(rule.activateLabelRegex.pattern)},'i');const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';};const c=[...document.querySelectorAll(${js(rule.activateSelector)})].filter(visible).filter(e=>{const t=norm(e.innerText||e.textContent);return t&&t.length<=180&&re.test(t);}).slice(0,${rule.activateMax});window.__audoibooPluginRunner={c,i:0};const step=()=>{const s=window.__audoibooPluginRunner;if(!s||s.i>=s.c.length)return;try{s.c[s.i++].click();}catch(_){}setTimeout(step,${rule.activateIntervalMs});};AudoibooPluginCapture.event('tracks='+c.length);step();}" else ""}
              return true;
            })();
        """.trimIndent()
    }
}
