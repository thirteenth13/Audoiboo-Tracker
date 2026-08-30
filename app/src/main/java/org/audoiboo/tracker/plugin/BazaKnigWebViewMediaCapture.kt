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

    fun capture(pageUrl: String, timeoutMs: Long = 25_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Baza-Knig URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedSet(LinkedHashSet<String>())
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val url = raw?.trim().orEmpty()
                if (isBookAudio(url)) found += url
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
                    if (!message.isNullOrBlank() && diagnostics.size < 50) diagnostics += "js:$message"
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
                    listOf(900L, 4_000L, 8_000L, 12_000L, 17_500L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(ACTIVATE_AND_SCAN, null) }, delay)
                    }
                    // Track buttons are activated with a stagger. The old 12.5 s cutoff could return
                    // after the first media URL while the rest of the player was still being exposed.
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("captured") }, 20_500L)
                }
            }

            handler.postDelayed({ finish("timeout") }, timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooBazaCapture"
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
            val name = URI(url).path.substringAfterLast('/'); name.substringBeforeLast('.').toIntOrNull()
        }.getOrNull()

        fun isLikelyTrackLabel(text: String): Boolean = text.trim().length in 1..180 && TRACK_LABEL.matches(text.trim())

        private val INSTALL_HOOKS = """
            (() => {
              if (window.__audoibooBazaHooks) return 'already';
              window.__audoibooBazaHooks = true;
              const emit = u => { try { if (u) AudoibooBazaCapture.media(String(u)); } catch (_) {} };
              const src = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              if (src && src.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', { configurable: src.configurable, enumerable: src.enumerable, get: src.get, set(v) { emit(v); return src.set.call(this, v); } });
              const oldSet = Element.prototype.setAttribute;
              Element.prototype.setAttribute = function(n,v) { if (String(n).toLowerCase()==='src') emit(v); return oldSet.call(this,n,v); };
              const NativeAudio = window.Audio;
              window.Audio = function(src) { const a=new NativeAudio(src); emit(src); return a; };
              window.Audio.prototype = NativeAudio.prototype;
              return 'installed';
            })();
        """.trimIndent()

        private val ACTIVATE_AND_SCAN = """
            (() => {
              const emit = u => { try { if (u) AudoibooBazaCapture.media(String(u)); } catch (_) {} };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim();
              document.querySelectorAll('audio[src],source[src],a[href]').forEach(e => emit(e.src || e.href));
              try { performance.getEntriesByType('resource').forEach(e => emit(e.name)); } catch (_) {}
              const html = document.documentElement.innerHTML.replaceAll('\\/','/');
              (html.match(/https?:[^\"'<>\\s]+\.mp3(?:\?[^\"'<>\\s]*)?/gi) || []).forEach(emit);
              const visible = e => { const r=e.getBoundingClientRect(); const s=getComputedStyle(e); return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; };
              const nodes = [...document.querySelectorAll('button,a,div,span,li')].filter(visible);
              const likely = nodes.filter(e => {
                const t = norm(e.innerText || e.textContent); if (!t || t.length > 180) return false;
                const own = /^\d{1,3}(?:[\s._:)-]+.*)?$/i.test(t) || /(?:трек|глава|часть)\s*\d+/i.test(t) || /(?:play|слушать|воспроизвести)/i.test(t);
                if (!own) return false;
                return ![...e.children].some(c => { const ct=norm(c.innerText||c.textContent); return ct && ct.length<=180 && (/^\d{1,3}(?:[\s._:)-]+.*)?$/i.test(ct) || /(?:трек|глава|часть)\s*\d+/i.test(ct)); });
              }).slice(0, 100);
              likely.forEach((e,i) => setTimeout(() => { try { e.click(); } catch (_) {} }, i*180));
              AudoibooBazaCapture.event('clicks='+likely.length); return likely.length;
            })();
        """.trimIndent()
    }
}
