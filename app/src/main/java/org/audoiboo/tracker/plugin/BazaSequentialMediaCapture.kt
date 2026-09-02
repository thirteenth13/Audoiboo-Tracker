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

/** Captures Baza media from network traffic and the JavaScript player state. */
class BazaSequentialMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 35_000L, onComplete: (Result) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val found = LinkedHashSet<String>()
            val relativeTracks = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun mediaBase(): URI? = synchronized(found) {
                found.firstOrNull { runCatching { URI(it).host?.contains("redirectto.cc") == true }.getOrDefault(false) }
                    ?.let { runCatching { URI(it).resolve(".") }.getOrNull() }
            }

            fun resolveRelativeTracks() {
                val base = mediaBase() ?: return
                synchronized(relativeTracks) {
                    relativeTracks.forEach { raw ->
                        val url = runCatching { base.resolve(raw).toString() }.getOrNull() ?: return@forEach
                        if (BazaKnigWebViewMediaCapture.isBookAudio(url)) synchronized(found) { if (found.size < 350) found += url }
                    }
                }
            }

            fun remember(raw: String?) {
                val value = raw?.trim()?.replace("\\/", "/").orEmpty()
                if (value.isBlank()) return
                if (Regex("^\\d{1,4}\\.mp3(?:\\?.*)?$", RegexOption.IGNORE_CASE).matches(value)) {
                    synchronized(relativeTracks) { relativeTracks += value }
                    resolveRelativeTracks()
                    return
                }
                val url = runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return
                if (!BazaKnigWebViewMediaCapture.isBookAudio(url)) return
                synchronized(found) { if (found.size < 350) found += url }
                resolveRelativeTracks()
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                resolveRelativeTracks()
                handler.removeCallbacksAndMessages(null)
                val media = synchronized(found) {
                    found.toList().sortedWith(compareBy({ BazaKnigWebViewMediaCapture.trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                }
                diagnostics += reason
                diagnostics += "relative=${synchronized(relativeTracks) { relativeTracks.size }}"
                diagnostics += "media=${media.size}"
                runCatching {
                    webView.stopLoading(); webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE); webView.destroy()
                }
                onComplete(Result(pageUrl, media, diagnostics.toList()))
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
                @JavascriptInterface fun relative(name: String?) = handler.post { remember(name) }
                @JavascriptInterface fun event(message: String?) = handler.post {
                    if (!message.isNullOrBlank() && diagnostics.size < 240) diagnostics += "js:$message"
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val next = request?.url?.toString().orEmpty()
                    if (next.isNotBlank() && next != pageUrl && BazaKnigWebViewMediaCapture.isAllowedPage(next)) {
                        diagnostics += "blocked-nav"; return true
                    }
                    return false
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!BazaKnigWebViewMediaCapture.isAllowedPage(url) || finished.get()) return
                    diagnostics += "loaded"
                    view.evaluateJavascript(SCRIPT, null)
                    listOf(2_000L, 5_000L, 9_000L, 14_000L, 20_000L, 27_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(RESCAN, null) }, delay)
                    }
                }
            }
            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(35_000L))
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooBazaSequential"

        private val SCRIPT = """
            (() => {
              if (window.__audoibooBazaV3) return 'already';
              window.__audoibooBazaV3 = true;
              document.addEventListener('click', e => {
                try { const a=e.target?.closest?.('a[href]'); if(a){const h=String(a.getAttribute('href')||''); if(h && h!=='#' && !h.toLowerCase().startsWith('javascript:')) e.preventDefault();} } catch(_){}
              }, true);

              const emit = raw => { try { if(raw) AudoibooBazaSequential.media(String(raw).replaceAll('\\/','/')); } catch(_){} };
              const rel = raw => { try { if(raw) AudoibooBazaSequential.relative(String(raw).replaceAll('\\/','/')); } catch(_){} };
              const scanText = text => {
                try {
                  const v=String(text||'').replaceAll('\\/','/');
                  (v.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+\.mp3(?:\?[^\"'<>\s]*)?/gi)||[]).slice(0,1500).forEach(emit);
                  (v.match(/(?:^|[\"'\s,:\[])(\d{1,4}\.mp3(?:\?[^\"'<>\s,\]]*)?)/gi)||[]).slice(0,1000).forEach(x => {
                    const m=String(x).match(/(\d{1,4}\.mp3(?:\?[^\"'<>\s,\]]*)?)/i); if(m) rel(m[1]);
                  });
                } catch(_){}
              };
              window.__audoibooBazaScanText=scanText;

              const nativeFetch=window.fetch;
              if(nativeFetch) window.fetch=function(...args){args.forEach(scanText); return nativeFetch.apply(this,args).then(r=>{try{r.clone().text().then(scanText).catch(()=>{});}catch(_){} return r;});};
              const nativeOpen=XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open=function(...args){args.forEach(scanText); this.addEventListener('load',()=>{try{scanText(this.responseText);}catch(_){}}); return nativeOpen.apply(this,args);};
              const src=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');
              if(src?.set) Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:src.configurable,enumerable:src.enumerable,get:src.get,set(v){emit(v);return src.set.call(this,v);}});
              return window.__audoibooBazaRescan();
            })();
        """.trimIndent()

        private val RESCAN = """
            (() => window.__audoibooBazaRescan ? window.__audoibooBazaRescan() : false)();
        """.trimIndent()

        @Suppress("unused")
        private val RESCAN_IMPL = Unit

        init {
            // SCRIPT installs this function through the appended implementation below.
        }

        private val UNUSED = """
        """
    }
}
