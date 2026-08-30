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

/** Device-side media discovery for Lis10book's dynamically loaded player. */
class Lis10BookWebViewMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 24_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Lis10book URL" }
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
                    listOf(1_000L, 3_500L, 6_500L, 10_000L, 14_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(ACTIVATE_AND_SCAN, null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("captured") }, 18_000L)
                }
            }

            handler.postDelayed({ finish("timeout") }, timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooLis10Capture"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "m3u8")

        fun isAllowedPage(url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme?.lowercase() in setOf("http", "https") &&
                (host == "lis10book.com" || host.endsWith(".lis10book.com")) &&
                uri.path.orEmpty().startsWith("/audio/")
        }.getOrDefault(false)

        fun isAudioUrl(url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase().orEmpty()
            val path = uri.path.orEmpty().lowercase()
            scheme in setOf("http", "https") && path.substringAfterLast('.', "") in AUDIO_EXTENSIONS
        }.getOrDefault(false)

        private val INSTALL_HOOKS = """
            (() => {
              if (window.__audoibooLis10Hooks) return 'already';
              window.__audoibooLis10Hooks = true;
              const emit = u => { try { if (u) AudoibooLis10Capture.media(String(u)); } catch (_) {} };
              const oldFetch = window.fetch;
              if (oldFetch) window.fetch = function(input) {
                try { emit(typeof input === 'string' ? input : input.url); } catch (_) {}
                return oldFetch.apply(this, arguments);
              };
              const oldOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(method, url) {
                emit(url); return oldOpen.apply(this, arguments);
              };
              const src = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              if (src && src.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: src.configurable, enumerable: src.enumerable, get: src.get,
                set(v) { emit(v); return src.set.call(this, v); }
              });
              const NativeAudio = window.Audio;
              window.Audio = function(src) { const a = new NativeAudio(src); emit(src); return a; };
              window.Audio.prototype = NativeAudio.prototype;
              return 'installed';
            })();
        """.trimIndent()

        private val ACTIVATE_AND_SCAN = """
            (() => {
              const emit = u => { try { if (u) AudoibooLis10Capture.media(String(u)); } catch (_) {} };
              const norm = s => (s || '').replace(/\s+/g, ' ').trim();
              document.querySelectorAll('audio[src],source[src],a[href]').forEach(e => emit(e.src || e.href));
              try { performance.getEntriesByType('resource').forEach(e => emit(e.name)); } catch (_) {}
              const html = document.documentElement.innerHTML.replaceAll('\\/','/');
              (html.match(/https?:[^"'<>\\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\?[^"'<>\\s]*)?/gi) || []).forEach(emit);
              document.querySelectorAll('audio').forEach(a => { try { a.play(); } catch (_) {} });
              const nodes = [...document.querySelectorAll('button,a,div,span,li')];
              const likely = nodes.filter(e => {
                const t = norm(e.innerText || e.textContent);
                if (!t || t.length > 180) return false;
                return /(?:слушать|воспроизвести|play|▶)/i.test(t) ||
                       /^\d{1,3}(?:[\s._:)-]+.*)?$/.test(t);
              }).slice(0, 100);
              likely.forEach((e, i) => setTimeout(() => { try { e.click(); } catch (_) {} }, i * 160));
              AudoibooLis10Capture.event('clicks='+likely.length);
              return likely.length;
            })();
        """.trimIndent()
    }
}
