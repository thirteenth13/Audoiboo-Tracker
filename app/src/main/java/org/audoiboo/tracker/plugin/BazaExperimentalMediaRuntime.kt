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
import org.json.JSONObject
import java.net.URI
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Port of the older Baza multitrack WebView experiment that traversed every matching player row. */
object BazaExperimentalMediaRuntime {
    private const val BRIDGE = "AudoibooBazaCapture"

    fun capture(
        context: Context,
        manifest: PluginPackageManifest,
        rule: PluginMediaCaptureRule,
        pageUrl: String,
        onComplete: (PluginMediaCaptureResult) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            val found = LinkedHashSet<String>()
            val keys = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val url = runCatching {
                    val value = raw?.trim()?.replace("\\/", "/").orEmpty()
                    if (value.isBlank()) return@runCatching null
                    URI(pageUrl).resolve(value).toString()
                }.getOrNull() ?: return
                if (!PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, url)) return
                val key = PluginWebViewMediaCaptureRuntime.mediaKey(url) ?: url
                synchronized(found) {
                    if (found.size < rule.maxResults && keys.add(key)) found += url
                }
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = synchronized(found) {
                    found.toList().sortedWith(compareBy({ PluginWebViewMediaCaptureRuntime.trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                }
                diagnostics += reason
                diagnostics += "baza-media=${media.size}"
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
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
                    if (!message.isNullOrBlank() && diagnostics.size < 120) diagnostics += "baza:$message"
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url) || finished.get()) return
                    diagnostics += "baza-loaded"
                    view.evaluateJavascript(script(rule), null)
                    handler.postDelayed({ if (!finished.get()) finish("baza-finished") }, minOf(rule.timeoutMs, 35_000L))
                }
            }

            handler.postDelayed({ finish("baza-timeout") }, minOf(rule.timeoutMs + 1000L, 36_000L))
            webView.loadUrl(pageUrl)
        }
    }

    private fun script(rule: PluginMediaCaptureRule): String {
        val selector = JSONObject.quote(rule.activateSelector ?: "button,a,div,span,li")
        val label = JSONObject.quote(rule.activateLabelRegex?.pattern ?: "^(?:\\s*\\d{1,3}(?:[\\s._:)-]+.*)?|.*(?:трек|глава|часть)\\s*\\d+|.*(?:play|слушать|воспроизвести).*)$")
        val interval = rule.activateIntervalMs.coerceAtLeast(180L)
        return """
            (() => {
              if (window.__audoibooBazaExperiment) return;
              window.__audoibooBazaExperiment = true;
              const selector=$selector, labelRe=new RegExp($label,'i');
              const emit=u=>{try{if(u)$BRIDGE.media(new URL(String(u).replaceAll('\\/','/'),location.href).href)}catch(_){}};
              const scanText=t=>{try{const v=String(t||'').replaceAll('\\/','/');(v.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+\.mp3(?:\?[^\"'<>\s]*)?/gi)||[]).slice(0,1200).forEach(emit)}catch(_){}};
              const scan=()=>{
                document.querySelectorAll('*').forEach(e=>Array.from(e.attributes||[]).forEach(a=>{if(/^(src|href|data-|onclick|onplay)/i.test(a.name)){scanText(a.value);if(/^(src|href|data-src|data-url|data-file|data-mp3)$/i.test(a.name))emit(a.value)}}));
                document.querySelectorAll('script').forEach(s=>scanText(s.textContent||s.innerHTML));
                scanText(document.documentElement.innerHTML);
                try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){}
                try{Object.keys(window).filter(k=>/track|playlist|audio|player|book|sound|media|data|file/i.test(k)).slice(0,220).forEach(k=>{const v=window[k];if(typeof v==='string')scanText(v);else try{scanText(JSON.stringify(v))}catch(_){}})}catch(_){}
              };
              const nf=window.fetch;if(nf)window.fetch=function(...a){a.forEach(scanText);return nf.apply(this,a).then(r=>{try{r.clone().text().then(scanText).catch(()=>{})}catch(_){}return r})};
              const no=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(...a){a.forEach(scanText);this.addEventListener('load',()=>{try{scanText(this.responseText)}catch(_){}});return no.apply(this,a)};
              scan();
              const norm=s=>String(s||'').replace(/\s+/g,' ').trim();
              const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'};
              const nodes=[...document.querySelectorAll(selector)].filter(visible).filter(e=>{const t=norm(e.innerText||e.textContent);return t&&t.length<=180&&labelRe.test(t)}).slice(0,350);
              $BRIDGE.event('activation-start='+nodes.length);
              let i=0;
              const next=()=>{
                if(i>=nodes.length){scan();$BRIDGE.event('activation-complete='+nodes.length);return;}
                const e=nodes[i++];
                try{e.scrollIntoView({block:'center'});e.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,view:window}));e.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,view:window}));e.click()}catch(_){}
                setTimeout(()=>{scan();next()},$interval);
              };
              next();
            })();
        """.trimIndent()
    }
}
