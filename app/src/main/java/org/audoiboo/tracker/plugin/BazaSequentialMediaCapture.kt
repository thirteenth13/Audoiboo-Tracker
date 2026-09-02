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
import java.net.URI
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Captures Baza media from network traffic and JavaScript player state. */
class BazaSequentialMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 35_000L, onComplete: (Result) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val found = LinkedHashSet<String>()
            val relative = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun expand() {
                val base = synchronized(found) {
                    found.firstOrNull { runCatching { URI(it).host?.contains("redirectto.cc") == true }.getOrDefault(false) }
                }?.let { runCatching { URI(it).resolve(".") }.getOrNull() } ?: return
                synchronized(relative) {
                    relative.forEach { name ->
                        val url = runCatching { base.resolve(name).toString() }.getOrNull() ?: return@forEach
                        if (BazaKnigWebViewMediaCapture.isBookAudio(url)) synchronized(found) { if (found.size < 350) found += url }
                    }
                }
            }

            fun remember(raw: String?) {
                val value = raw?.trim()?.replace("\\/", "/").orEmpty()
                if (value.isBlank()) return
                if (Regex("^\\d{1,4}\\.mp3(?:\\?.*)?$", RegexOption.IGNORE_CASE).matches(value)) {
                    synchronized(relative) { relative += value }; expand(); return
                }
                val url = runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return
                if (BazaKnigWebViewMediaCapture.isBookAudio(url)) {
                    synchronized(found) { if (found.size < 350) found += url }; expand()
                }
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                expand(); handler.removeCallbacksAndMessages(null)
                val media = synchronized(found) { found.toList().sortedWith(compareBy({ BazaKnigWebViewMediaCapture.trackNumber(it) ?: Int.MAX_VALUE }, { it })) }
                diagnostics += reason
                diagnostics += "relative=${synchronized(relative) { relative.size }}"
                diagnostics += "media=${media.size}"
                runCatching { webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeJavascriptInterface(BRIDGE); webView.destroy() }
                onComplete(Result(pageUrl, media, diagnostics.toList()))
            }

            @SuppressLint("SetJavaScriptEnabled")
            webView.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = false
                userAgentString = userAgentString.replace("; wv", "")
            }
            webView.addJavascriptInterface(object {
                @JavascriptInterface fun media(url: String?) = handler.post { remember(url) }
                @JavascriptInterface fun relative(name: String?) = handler.post { remember(name) }
                @JavascriptInterface fun event(message: String?) = handler.post { if (!message.isNullOrBlank() && diagnostics.size < 240) diagnostics += "js:$message" }
            }, BRIDGE)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString()); return super.shouldInterceptRequest(view, request)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val next = request?.url?.toString().orEmpty()
                    if (next.isNotBlank() && next != pageUrl && BazaKnigWebViewMediaCapture.isAllowedPage(next)) { diagnostics += "blocked-nav"; return true }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!BazaKnigWebViewMediaCapture.isAllowedPage(url) || finished.get()) return
                    diagnostics += "loaded"; view.evaluateJavascript(SCRIPT, null)
                    listOf(2_000L,5_000L,9_000L,14_000L,20_000L,27_000L).forEach { d -> handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(RESCAN, null) }, d) }
                }
            }
            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(35_000L)); webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooBazaSequential"
        private val SCRIPT = """
            (() => {
              if(window.__audoibooBazaV4) return 'already'; window.__audoibooBazaV4=true;
              const emit=x=>{try{if(x)AudoibooBazaSequential.media(String(x).replaceAll('\\/','/'));}catch(_){}};
              const rel=x=>{try{if(x)AudoibooBazaSequential.relative(String(x).replaceAll('\\/','/'));}catch(_){}};
              const scanText=text=>{try{const v=String(text||'').replaceAll('\\/','/');
                (v.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+\.mp3(?:\?[^\"'<>\s]*)?/gi)||[]).slice(0,1500).forEach(emit);
                (v.match(/(?:^|[\"'\s,:\[])(\d{1,4}\.mp3(?:\?[^\"'<>\s,\]]*)?)/gi)||[]).slice(0,1000).forEach(x=>{const m=String(x).match(/(\d{1,4}\.mp3(?:\?[^\"'<>\s,\]]*)?)/i);if(m)rel(m[1]);});
              }catch(_){}};
              const walk=(value,depth,seen,state)=>{if(value==null||depth>6||state.n>7000)return; const t=typeof value;
                if(t==='string'){scanText(value);return;} if(t!=='object'&&t!=='function')return;
                try{if(seen.has(value))return;seen.add(value);state.n++;Object.keys(value).slice(0,500).forEach(k=>{let c;try{c=value[k];}catch(_){return;}if(typeof c==='string')scanText(c);else if(depth<6&&(Array.isArray(c)||/track|play|audio|list|data|book|file|src|url|sound|media|item/i.test(k)))walk(c,depth+1,seen,state);});}catch(_){}};
              window.__audoibooBazaRescan=()=>{let globals=0;try{
                document.querySelectorAll('audio,source').forEach(e=>{emit(e.currentSrc);emit(e.src);emit(e.getAttribute('src'));});
                document.querySelectorAll('*').forEach(e=>Array.from(e.attributes||[]).forEach(a=>{if(/^(src|href|data-|onclick|onplay)/i.test(a.name))scanText(a.value);}));
                document.querySelectorAll('script').forEach(s=>scanText(s.textContent||s.innerHTML)); scanText(document.documentElement.innerHTML);
                performance.getEntriesByType('resource').forEach(e=>emit(e.name));
                const seen=new WeakSet(),state={n:0}; Object.keys(window).filter(k=>/track|playlist|audio|player|book|sound|media|data|file/i.test(k)).slice(0,220).forEach(k=>{try{walk(window[k],0,seen,state);}catch(_){}});globals=state.n;
                AudoibooBazaSequential.event('state='+globals);
              }catch(_){} return globals;};
              document.addEventListener('click',e=>{try{const a=e.target?.closest?.('a[href]');if(a){const h=String(a.getAttribute('href')||'');if(h&&h!=='#'&&!h.toLowerCase().startsWith('javascript:'))e.preventDefault();}}catch(_){}},true);
              const nf=window.fetch;if(nf)window.fetch=function(...a){a.forEach(scanText);return nf.apply(this,a).then(r=>{try{r.clone().text().then(scanText).catch(()=>{});}catch(_){}return r;});};
              const no=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(...a){a.forEach(scanText);this.addEventListener('load',()=>{try{scanText(this.responseText);}catch(_){}});return no.apply(this,a);};
              const sd=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(sd?.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:sd.configurable,enumerable:sd.enumerable,get:sd.get,set(v){emit(v);return sd.set.call(this,v);}});
              return window.__audoibooBazaRescan();
            })();
        """.trimIndent()
        private val RESCAN = """(()=>window.__audoibooBazaRescan?window.__audoibooBazaRescan():false)();"""
    }
}
