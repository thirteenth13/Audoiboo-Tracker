package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Host-owned WebView executor configured by media-capture.json inside a plugin package. */
data class PluginMediaCaptureConfig(
    val pluginId: String,
    val pageHosts: Set<String>,
    val mediaHosts: Set<String>,
    val extensions: Set<String>,
    val maxResults: Int,
    val timeoutMs: Long,
    val activationSelector: String,
    val labelRegex: String,
    val intervalMs: Long,
    val outputType: DownloadType,
    val scanScripts: Boolean,
    val scanAttributes: Boolean,
    val scanPerformance: Boolean,
    val scanGlobals: Boolean
) {
    fun supportsPage(url: String): Boolean = hostMatches(url, pageHosts)
    fun acceptsMedia(url: String): Boolean = runCatching {
        val uri = URI(url.trim())
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.lowercase().orEmpty()
        uri.scheme?.lowercase() in setOf("http", "https") &&
            mediaHosts.any { host == it || host.endsWith(".$it") } &&
            extensions.any { path.endsWith(".${it.lowercase()}") }
    }.getOrDefault(false)

    companion object {
        fun load(pluginId: String, packageDir: File): PluginMediaCaptureConfig? {
            val file = File(packageDir, "media-capture.json")
            if (!file.isFile) return null
            return runCatching {
                val root = JSONObject(file.readText())
                val activate = root.optJSONObject("activate") ?: JSONObject()
                val scan = root.optJSONObject("scan") ?: JSONObject()
                PluginMediaCaptureConfig(
                    pluginId = pluginId,
                    pageHosts = root.stringSet("pageHosts"),
                    mediaHosts = root.stringSet("mediaHosts"),
                    extensions = root.stringSet("extensions").ifEmpty { setOf("mp3") },
                    maxResults = root.optInt("maxResults", 350).coerceIn(1, 1000),
                    timeoutMs = root.optLong("timeoutMs", 35_000L).coerceIn(5_000L, 90_000L),
                    activationSelector = activate.optString("selector", "button,a,div,span,li").take(500),
                    labelRegex = activate.optString("labelRegex", "(?i)(?:^\\s*\\d{1,3}(?:[\\s._:)-]+.*)?$|(?:трек|глава|часть)\\s*\\d+|play|слушать|воспроизвести)").take(1000),
                    intervalMs = activate.optLong("intervalMs", 450L).coerceIn(100L, 3000L),
                    outputType = runCatching { DownloadType.valueOf(root.optString("outputType", "DIRECT_FILE")) }.getOrDefault(DownloadType.DIRECT_FILE),
                    scanScripts = scan.optBoolean("scripts", true),
                    scanAttributes = scan.optBoolean("attributes", true),
                    scanPerformance = scan.optBoolean("performance", true),
                    scanGlobals = scan.optBoolean("globals", true)
                ).takeIf { it.pageHosts.isNotEmpty() && it.mediaHosts.isNotEmpty() }
            }.getOrNull()
        }

        private fun JSONObject.stringSet(key: String): Set<String> {
            val array = optJSONArray(key) ?: return emptySet()
            return buildSet {
                for (i in 0 until array.length()) array.optString(i).trim().lowercase().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }

        private fun hostMatches(url: String, hosts: Set<String>): Boolean = runCatching {
            val uri = URI(url.trim())
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme?.lowercase() in setOf("http", "https") && hosts.any { host == it || host.endsWith(".$it") }
        }.getOrDefault(false)
    }
}

class PluginWebViewMediaCapture(private val context: Context, private val config: PluginMediaCaptureConfig) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    @SuppressLint("SetJavaScriptEnabled")
    fun capture(pageUrl: String, onComplete: (Result) -> Unit) {
        require(config.supportsPage(pageUrl)) { "Unsupported ${config.pluginId} URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedSet(LinkedHashSet<String>())
            val diagnostics = Collections.synchronizedList(mutableListOf<String>())
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false

            fun remember(raw: String?) {
                if (raw.isNullOrBlank() || found.size >= config.maxResults) return
                val absolute = runCatching { URI(pageUrl).resolve(raw.trim()).toString() }.getOrNull() ?: return
                if (config.acceptsMedia(absolute)) found += absolute
            }
            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                diagnostics += reason
                handler.removeCallbacksAndMessages(null)
                val media = synchronized(found) { found.toList() }.sortedWith(compareBy({ trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                runCatching {
                    webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeJavascriptInterface(BRIDGE); webView.destroy()
                }
                onComplete(Result(pageUrl, media, diagnostics.toList()))
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface fun media(url: String?) = handler.post { remember(url) }
                @JavascriptInterface fun event(message: String?) = handler.post {
                    if (!message.isNullOrBlank() && diagnostics.size < 100) diagnostics += message
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!config.supportsPage(url)) return
                    diagnostics += "loaded"
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!config.supportsPage(url)) return
                    view.evaluateJavascript(script(config), null)
                    handler.postDelayed({ if (!finished.get() && found.isNotEmpty()) finish("captured") }, (config.timeoutMs - 750L).coerceAtLeast(4_000L))
                }
            }
            handler.postDelayed({ finish("timeout") }, config.timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    private fun script(c: PluginMediaCaptureConfig): String {
        val selector = JSONObject.quote(c.activationSelector)
        val regex = JSONObject.quote(c.labelRegex)
        val extensions = c.extensions.joinToString("|") { Regex.escape(it) }
        return """
            (() => {
              if (window.__audoibooPluginCaptureRunning) return;
              window.__audoibooPluginCaptureRunning = true;
              const selector = $selector;
              const labelRe = new RegExp($regex, 'i');
              const mediaRe = new RegExp('(?:https?:\\/\\/|\\/\\/|\\/)[^\\"\\'<>\\s]+\\.(?:$extensions)(?:\\?[^\\"\\'<>\\s]*)?', 'gi');
              const emit = u => { try { if (u) $BRIDGE.media(new URL(String(u).replaceAll('\\\\/','/'), location.href).href); } catch (_) {} };
              const scanText = text => { try { (String(text || '').match(mediaRe) || []).slice(0,1000).forEach(emit); } catch (_) {} };
              const scan = () => {
                ${if (c.scanAttributes) "document.querySelectorAll('*').forEach(e => Array.from(e.attributes || []).forEach(a => { if (/^(src|href|data-|onclick|onplay)/i.test(a.name)) { scanText(a.value); if (/^(src|href|data-src|data-url|data-file|data-mp3)$/i.test(a.name)) emit(a.value); } }));" else ""}
                ${if (c.scanScripts) "document.querySelectorAll('script').forEach(s => scanText(s.textContent || s.innerHTML)); scanText(document.documentElement.innerHTML);" else ""}
                ${if (c.scanPerformance) "try { performance.getEntriesByType('resource').forEach(e => emit(e.name)); } catch (_) {}" else ""}
                ${if (c.scanGlobals) "try { Object.keys(window).filter(k => /track|playlist|audio|player|book|sound|media|data|file/i.test(k)).slice(0,180).forEach(k => { const v=window[k]; if (typeof v==='string') scanText(v); else { try { scanText(JSON.stringify(v)); } catch (_) {} } }); } catch (_) {}" else ""}
              };
              const nativeFetch = window.fetch;
              if (nativeFetch) window.fetch = function(...args) { args.forEach(scanText); return nativeFetch.apply(this,args).then(r => { try { r.clone().text().then(scanText).catch(()=>{}); } catch (_) {} return r; }); };
              const nativeOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(...args) { args.forEach(scanText); this.addEventListener('load',()=>{ try { scanText(this.responseText); } catch (_) {} }); return nativeOpen.apply(this,args); };
              scan();
              const norm = s => String(s || '').replace(/\\s+/g,' ').trim();
              const visible = e => { const r=e.getBoundingClientRect(), s=getComputedStyle(e); return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; };
              const nodes = [...document.querySelectorAll(selector)].filter(visible).filter(e => { const t=norm(e.innerText||e.textContent); return t && t.length<=180 && labelRe.test(t); }).slice(0,350);
              let i=0;
              const next=()=>{ if(i>=nodes.length){ scan(); $BRIDGE.event('activation-complete='+nodes.length); return; } try{ nodes[i++].click(); }catch(_){} setTimeout(()=>{ scan(); next(); }, ${c.intervalMs}); };
              next();
              $BRIDGE.event('activation-start='+nodes.length);
            })();
        """.trimIndent()
    }

    private fun trackNumber(url: String): Int? = runCatching {
        Uri.parse(url).lastPathSegment?.substringBefore('?')?.substringBeforeLast('.')?.split(Regex("[^0-9]+"))?.mapNotNull { it.toIntOrNull() }?.lastOrNull()
    }.getOrNull()

    companion object { private const val BRIDGE = "AudoibooPluginCapture" }
}
