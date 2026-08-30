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

/** Device-side media discovery for Izib/PDA audiobook pages. */
class IzibWebViewMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 22_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Izib URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedSet(LinkedHashSet<String>())
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val url = raw?.trim().orEmpty()
                if (isAudioUrl(url)) found += url
            }
            fun snapshot(): List<String> = synchronized(found) { found.toList() }
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
            }, BRIDGE)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!isAllowedPage(url)) return
                    view.evaluateJavascript(INSTALL_HOOKS, null)
                    listOf(700L, 2_500L, 5_000L, 8_000L, 12_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(ACTIVATE_AND_SCAN, null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("captured") }, 16_000L)
                }
            }
            handler.postDelayed({ finish("timeout") }, timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooIzibCapture"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "m3u8")

        fun isAllowedPage(url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme?.lowercase() in setOf("http", "https") &&
                (host == "pda.izib.uk" || host == "izib.uk" || host.endsWith(".izib.uk")) &&
                Regex("""^/art\d+""").containsMatchIn(uri.path.orEmpty())
        }.getOrDefault(false)

        fun isAudioUrl(url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase().orEmpty()
            val path = uri.path.orEmpty().lowercase()
            scheme in setOf("http", "https") && path.substringAfterLast('.', "") in AUDIO_EXTENSIONS
        }.getOrDefault(false)

        private val INSTALL_HOOKS = """
            (() => {
              if (window.__audoibooIzibHooks) return 'already';
              window.__audoibooIzibHooks = true;
              const emit = u => { try { if (u) AudoibooIzibCapture.media(String(u)); } catch (_) {} };
              const oldFetch = window.fetch;
              if (oldFetch) window.fetch = function(input) {
                try { emit(typeof input === 'string' ? input : input.url); } catch (_) {}
                return oldFetch.apply(this, arguments);
              };
              const oldOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) { emit(url); return oldOpen.apply(this, arguments); };
              const src = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              if (src && src.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: src.configurable, enumerable: src.enumerable, get: src.get,
                set(v) { emit(v); return src.set.call(this, v); }
              });
              return 'installed';
            })();
        """.trimIndent()

        private val ACTIVATE_AND_SCAN = """
            (() => {
              const emit = u => { try { if (u) AudoibooIzibCapture.media(String(u)); } catch (_) {} };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim();
              document.querySelectorAll('audio[src],source[src],a[href]').forEach(e => emit(e.src || e.href));
              try { performance.getEntriesByType('resource').forEach(e => emit(e.name)); } catch (_) {}
              const html = document.documentElement.innerHTML.replaceAll('\\/','/');
              (html.match(/https?:[^"'<>\\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\?[^"'<>\\s]*)?/gi) || []).forEach(emit);
              document.querySelectorAll('audio').forEach(a => { try { a.play(); } catch (_) {} });
              const likely = [...document.querySelectorAll('button,a,div,span,li')].filter(e => {
                const t = norm(e.innerText || e.textContent);
                return t && t.length <= 180 &&
                  (/(?:слушать|воспроизвести|play|▶)/i.test(t) || /^\d{1,3}(?:[\s._:)-]+.*)?$/.test(t));
              }).slice(0, 100);
              likely.forEach((e, i) => setTimeout(() => { try { e.click(); } catch (_) {} }, i * 150));
              return likely.length;
            })();
        """.trimIndent()
    }
}
