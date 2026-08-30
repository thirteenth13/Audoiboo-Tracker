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
import java.net.URI
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Device-side media discovery for Baza-Knig pages. */
class BazaKnigWebViewMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 30_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Baza-Knig URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedSet(LinkedHashSet<String>())
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val url = raw?.trim().orEmpty()
                if (!isBookAudio(url)) return
                synchronized(found) {
                    if (found.size < MAX_MEDIA_URLS) found += url
                }
            }

            fun snapshot(): List<String> = synchronized(found) { found.toList() }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                val media = snapshot().sortedWith(compareBy({ trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                diagnostics += reason
                diagnostics += "media=${media.size}"
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading(); webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
                    (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy()
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
                @JavascriptInterface fun event(message: String?) = handler.post {
                    if (!message.isNullOrBlank() && diagnostics.size < 80) diagnostics += "js:$message"
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!isAllowedPage(url)) return
                    diagnostics += "loaded"
                    view.evaluateJavascript(INSTALL_HOOKS, null)
                    listOf(900L, 4_000L, 8_000L, 12_000L, 17_000L, 21_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(ACTIVATE_AND_SCAN, null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("captured-playlist") }, 24_500L)
                }
            }

            handler.postDelayed({ finish("timeout") }, timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooBazaCapture"
        private const val MAX_MEDIA_URLS = 350
        private val TRACK_LABEL = Regex("""^\s*(?:\d{1,3}(?:[\s._:)-]+.*)?|.*(?:трек|глава|часть)\s*\d+).*$""", RegexOption.IGNORE_CASE)

        fun isAllowedPage(url: String): Boolean = runCatching {
            val uri = URI(url.trim()); val host = uri.host?.lowercase().orEmpty()
            uri.scheme?.lowercase() in setOf("http", "https") &&
                (host == "baza-knig.info" || host == "baza-knig.top" || host.endsWith(".baza-knig.info") || host.endsWith(".baza-knig.top"))
        }.getOrDefault(false)

        fun isBookAudio(url: String): Boolean = runCatching {
            val uri = URI(url.trim()); val host = uri.host?.lowercase().orEmpty(); val path = uri.path?.lowercase().orEmpty()
            uri.scheme?.lowercase() in setOf("http", "https") && path.endsWith(".mp3") &&
                (host.endsWith(".redirectto.cc") || host == "redirectto.cc" || host.endsWith(".baza-knig.info") || host == "baza-knig.info" || host.endsWith(".baza-knig.top") || host == "baza-knig.top")
        }.getOrDefault(false)

        fun trackNumber(url: String): Int? = runCatching {
            val name = URI(url).path.substringAfterLast('/')
            Regex("(?<!\\d)(\\d{1,4})(?!\\d)")
                .findAll(name.substringBeforeLast('.'))
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .lastOrNull()
        }.getOrNull()

        fun isLikelyTrackLabel(text: String): Boolean = text.trim().length in 1..180 && TRACK_LABEL.matches(text.trim())

        private val INSTALL_HOOKS = """
            (() => {
              if (window.__audoibooBazaHooks) return 'already';
              window.__audoibooBazaHooks = true;

              const emitOne = raw => {
                try {
                  if (!raw) return;
                  const clean = String(raw).replaceAll('\\/','/').trim();
                  if (!clean) return;
                  AudoibooBazaCapture.media(new URL(clean, location.href).href);
                } catch (_) {}
              };
              const scanText = text => {
                try {
                  const value = String(text || '').replaceAll('\\/','/');
                  const urls = value.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+\.mp3(?:\?[^\"'<>\s]*)?/gi) || [];
                  urls.slice(0, 500).forEach(emitOne);
                  return urls.length;
                } catch (_) { return 0; }
              };
              window.__audoibooBazaScanText = scanText;

              const src = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              if (src && src.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: src.configurable, enumerable: src.enumerable, get: src.get,
                set(v) { emitOne(v); return src.set.call(this, v); }
              });
              const oldSet = Element.prototype.setAttribute;
              Element.prototype.setAttribute = function(n,v) {
                if (String(n).toLowerCase()==='src') emitOne(v);
                return oldSet.call(this,n,v);
              };
              const NativeAudio = window.Audio;
              window.Audio = function(src) { const a=new NativeAudio(src); emitOne(src); return a; };
              window.Audio.prototype = NativeAudio.prototype;

              if (window.fetch) {
                const nativeFetch = window.fetch;
                window.fetch = function(...args) {
                  return nativeFetch.apply(this,args).then(response => {
                    try { response.clone().text().then(scanText).catch(() => {}); } catch (_) {}
                    return response;
                  });
                };
              }

              const nativeOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(...args) {
                this.addEventListener('load', () => {
                  try { if (typeof this.responseText === 'string') scanText(this.responseText); } catch (_) {}
                });
                return nativeOpen.apply(this,args);
              };
              return 'installed';
            })();
        """.trimIndent()

        private val ACTIVATE_AND_SCAN = """
            (() => {
              const scanText = window.__audoibooBazaScanText || (() => 0);
              const emit = u => {
                try { if (u) AudoibooBazaCapture.media(new URL(String(u), location.href).href); } catch (_) {}
              };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim();

              document.querySelectorAll('audio[src],source[src],a[href]').forEach(e => emit(e.src || e.href));
              try { performance.getEntriesByType('resource').forEach(e => emit(e.name)); } catch (_) {}
              scanText(document.documentElement.innerHTML);

              // Several Baza player versions keep the full playlist in a JS object and only assign
              // one URL to <audio> at a time. Walk likely player globals shallowly to expose the list.
              const seen = new WeakSet();
              let visited = 0;
              const walk = (value, depth) => {
                if (depth > 4 || visited > 2500 || value == null) return;
                if (typeof value === 'string') { scanText(value); return; }
                if (typeof value !== 'object' && typeof value !== 'function') return;
                try {
                  if (seen.has(value)) return;
                  seen.add(value);
                  visited++;
                  const keys = Object.keys(value).slice(0, 250);
                  for (const key of keys) {
                    if (visited > 2500) break;
                    let child;
                    try { child = value[key]; } catch (_) { continue; }
                    if (typeof child === 'string') scanText(child);
                    else if (depth < 4 && (Array.isArray(child) || /track|play|audio|list|data|book|file|src|url/i.test(key))) walk(child, depth + 1);
                  }
                } catch (_) {}
              };
              try {
                Object.keys(window)
                  .filter(k => /track|playlist|audio|player|book|sound|media|data/i.test(k))
                  .slice(0, 100)
                  .forEach(k => { try { walk(window[k], 0); } catch (_) {} });
              } catch (_) {}

              const visible = e => { const r=e.getBoundingClientRect(); const s=getComputedStyle(e); return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; };
              const nodes = [...document.querySelectorAll('button,a,div,span,li')].filter(visible);
              const likely = nodes.filter(e => {
                const t = norm(e.innerText || e.textContent); if (!t || t.length > 180) return false;
                const own = /^\d{1,3}(?:[\s._:)-]+.*)?$/i.test(t) || /(?:трек|глава|часть)\s*\d+/i.test(t) || /(?:play|слушать|воспроизвести)/i.test(t);
                if (!own) return false;
                return ![...e.children].some(c => {
                  const ct=norm(c.innerText||c.textContent);
                  return ct && ct.length<=180 && (/^\d{1,3}(?:[\s._:)-]+.*)?$/i.test(ct) || /(?:трек|глава|часть)\s*\d+/i.test(ct));
                });
              }).slice(0, 220);
              likely.forEach((e,i) => setTimeout(() => { try { e.click(); } catch (_) {} }, i*95));
              AudoibooBazaCapture.event('clicks='+likely.length+' globals='+visited);
              return likely.length;
            })();
        """.trimIndent()
    }
}
