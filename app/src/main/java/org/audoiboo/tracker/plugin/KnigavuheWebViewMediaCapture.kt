package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Device-side media discovery for Knigavuhe pages.
 *
 * This deliberately runs in Android WebView so the request comes from the user's device/network
 * instead of a datacenter runner. Only public HTTP(S) audiobook media URLs are collected; cookies
 * and authorization data are never returned to plugins.
 */
class KnigavuheWebViewMediaCapture(private val context: Context) {
    data class Result(
        val pageUrl: String,
        val mediaUrls: List<String>,
        val diagnostics: List<String>
    )

    fun capture(
        pageUrl: String,
        timeoutMs: Long = 20_000L,
        onComplete: (Result) -> Unit
    ) {
        require(isAllowedPage(pageUrl)) { "Unsupported Knigavuhe URL" }
        Handler(Looper.getMainLooper()).post {
            val found = linkedSetOf<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val url = raw?.trim().orEmpty()
                if (isBookAudio(url)) found += url
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                diagnostics += reason
                diagnostics += "media=${found.size}"
                handler.removeCallbacksAndMessages(null)
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
                onComplete(Result(pageUrl, found.toList(), diagnostics.toList()))
            }

            @SuppressLint("SetJavaScriptEnabled")
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = userAgentString.replace("; wv", "")
            }

            webView.addJavascriptInterface(object {
                @JavascriptInterface
                fun media(url: String?) = handler.post { remember(url) }

                @JavascriptInterface
                fun event(message: String?) = handler.post {
                    if (!message.isNullOrBlank() && diagnostics.size < 40) diagnostics += "js:$message"
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!isAllowedPage(url)) return
                    diagnostics += "loaded:${Uri.parse(url).host}"
                    view.evaluateJavascript(INSTALL_HOOKS, null)
                    handler.postDelayed({
                        if (!finished.get()) view.evaluateJavascript(ACTIVATE_PLAYER, null)
                    }, 900L)
                    handler.postDelayed({
                        if (!finished.get()) view.evaluateJavascript(SCAN_PAGE, null)
                    }, 2_500L)
                    handler.postDelayed({
                        if (!finished.get() && found.isNotEmpty()) finish("captured")
                    }, 5_500L)
                }
            }

            handler.postDelayed({ finish("timeout") }, timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooMediaCapture"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac")

        fun isAllowedPage(url: String): Boolean = runCatching {
            val uri = Uri.parse(url)
            uri.scheme in setOf("http", "https") &&
                (uri.host == "knigavuhe.org" || uri.host == "m.knigavuhe.org" || uri.host?.endsWith(".knigavuhe.org") == true)
        }.getOrDefault(false)

        fun isBookAudio(url: String): Boolean = runCatching {
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase().orEmpty()
            val path = uri.path?.lowercase().orEmpty()
            val ext = path.substringAfterLast('.', "")
            uri.scheme in setOf("http", "https") &&
                (host == "knigavuhe.org" || host.endsWith(".knigavuhe.org")) &&
                path.contains("/audio/") && ext in AUDIO_EXTENSIONS
        }.getOrDefault(false)

        private val INSTALL_HOOKS = """
            (() => {
              if (window.__audoibooHooks) return 'already';
              window.__audoibooHooks = true;
              const emit = u => { try { if (u) AudoibooMediaCapture.media(String(u)); } catch (_) {} };
              const src = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              if (src && src.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: src.configurable, enumerable: src.enumerable,
                get: src.get,
                set(v) { emit(v); return src.set.call(this, v); }
              });
              const oldSet = Element.prototype.setAttribute;
              Element.prototype.setAttribute = function(n, v) {
                if (String(n).toLowerCase() === 'src' && (this instanceof HTMLMediaElement || this.tagName === 'SOURCE')) emit(v);
                return oldSet.call(this, n, v);
              };
              const NativeAudio = window.Audio;
              window.Audio = function(src) { const a = new NativeAudio(src); emit(src); return a; };
              window.Audio.prototype = NativeAudio.prototype;
              document.querySelectorAll('audio[src],source[src]').forEach(e => emit(e.src));
              return 'installed';
            })();
        """.trimIndent()

        private val ACTIVATE_PLAYER = """
            (() => {
              const visible = e => { const r=e.getBoundingClientRect(); const s=getComputedStyle(e); return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; };
              const candidates = [...document.querySelectorAll('button,a,div,span')].filter(visible);
              const full = candidates.find(e => /слушать полностью/i.test((e.innerText||'').trim()));
              if (full) { full.click(); AudoibooMediaCapture.event('clicked-full'); }
              const tracks = candidates.filter(e => /^\s*(?:\d{2}|.*_\d+)\s*(?:\d+:\d+(?::\d+)?)?\s*$/.test((e.innerText||'').trim())).slice(0, 60);
              for (const t of tracks) { try { t.click(); } catch (_) {} }
              AudoibooMediaCapture.event('track-candidates='+tracks.length);
              return tracks.length;
            })();
        """.trimIndent()

        private val SCAN_PAGE = """
            (() => {
              const emit = u => { try { if (u) AudoibooMediaCapture.media(String(u)); } catch (_) {} };
              document.querySelectorAll('audio[src],source[src],a[href]').forEach(e => emit(e.src || e.href));
              const html = document.documentElement.innerHTML.replaceAll('\\/','/');
              const urls = html.match(/https?:[^\"'<>\\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\?[^\"'<>\\s]*)?/gi) || [];
              urls.forEach(emit);
              AudoibooMediaCapture.event('scan='+urls.length);
              return urls.length;
            })();
        """.trimIndent()
    }
}
