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
import java.net.URI
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Device-side media discovery for Knigavuhe pages. */
class KnigavuheWebViewMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 20_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Knigavuhe URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedSet(LinkedHashSet<String>())
            val foundPaths = Collections.synchronizedSet(LinkedHashSet<String>())
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val url = raw?.trim().orEmpty()
                if (!isBookAudio(url)) return
                val key = runCatching {
                    val u = URI(url)
                    "${u.scheme?.lowercase()}://${u.host?.lowercase()}${u.path}"
                }.getOrNull() ?: return
                synchronized(found) {
                    if (found.size >= MAX_MEDIA_URLS || !foundPaths.add(key)) return
                    found += url
                }
            }

            fun snapshot(): List<String> = synchronized(found) { found.toList() }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                val media = snapshot()
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
                    if (!message.isNullOrBlank() && diagnostics.size < 60) diagnostics += "js:$message"
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
                    handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(ACTIVATE_PLAYER, null) }, 900L)
                    listOf(2_500L, 6_500L, 10_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(SCAN_PLAYER, null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("captured") }, 13_500L)
                }
            }

            handler.postDelayed({ finish("timeout") }, timeoutMs)
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooMediaCapture"
        private const val MAX_MEDIA_URLS = 120
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac")
        private val TRACK_LABEL = Regex("""(?ix)^\s*(?:\d{1,3}(?:[\s._:)-]+.+)?|.+_\d+)(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?\s*$""")

        fun isAllowedPage(url: String): Boolean = runCatching {
            val uri = URI(url); val scheme = uri.scheme?.lowercase().orEmpty(); val host = uri.host?.lowercase().orEmpty()
            scheme in setOf("http", "https") && (host == "knigavuhe.org" || host.endsWith(".knigavuhe.org"))
        }.getOrDefault(false)

        fun isBookAudio(url: String): Boolean = runCatching {
            val uri = URI(url); val scheme = uri.scheme?.lowercase().orEmpty(); val host = uri.host?.lowercase().orEmpty()
            val path = uri.path?.lowercase().orEmpty(); val ext = path.substringAfterLast('.', "")
            scheme in setOf("http", "https") && (host == "knigavuhe.org" || host.endsWith(".knigavuhe.org")) &&
                path.contains("/audio/") && ext in AUDIO_EXTENSIONS
        }.getOrDefault(false)

        fun isLikelyTrackLabel(text: String): Boolean = text.trim().length in 1..180 && TRACK_LABEL.matches(text.trim())

        private val INSTALL_HOOKS = """
            (() => {
              if (window.__audoibooHooks) return 'already';
              window.__audoibooHooks = true;
              const emit = u => { try { if (u) AudoibooMediaCapture.media(String(u)); } catch (_) {} };
              const src = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              if (src && src.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: src.configurable, enumerable: src.enumerable, get: src.get,
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
              const norm = s => (s || '').replace(/\s+/g, ' ').trim();
              const visible = e => { const r=e.getBoundingClientRect(); const s=getComputedStyle(e); return r.width>0 && r.height>0 && s.visibility!=='hidden' && s.display!=='none'; };
              const all = [...document.querySelectorAll('button,a,div,span,li,label')].filter(visible);
              const full = all.find(e => /слушать полностью/i.test(norm(e.innerText)));
              if (full) { try { full.click(); AudoibooMediaCapture.event('clicked-full'); } catch (_) {} }
              const largeLabel = all.find(e => /большие отрезки/i.test(norm(e.innerText)));
              if (largeLabel) {
                const checkbox = largeLabel.matches('input[type=checkbox]') ? largeLabel :
                  (largeLabel.querySelector('input[type=checkbox]') || document.querySelector('input[type=checkbox][name*=large i],input[type=checkbox][id*=large i]'));
                if (checkbox && !checkbox.checked) { try { checkbox.click(); checkbox.dispatchEvent(new Event('change',{bubbles:true})); AudoibooMediaCapture.event('enabled-large-segments'); } catch (_) {} }
              }
              const leaf = e => ![...e.children].some(c => { const t=norm(c.innerText||c.textContent); return t && t.length<=180 && (/^\d{1,3}(?:[\s._:)-]+.+)?(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?$/.test(t) || /_\d+(?:\s|$)/.test(t)); });
              const tracks = all.filter(e => { const t=norm(e.innerText||e.textContent); return t && t.length<=180 && leaf(e) && (/^\d{1,3}(?:[\s._:)-]+.+)?(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?$/.test(t) || /_\d+(?:\s|$)/.test(t)); }).slice(0, 100);
              tracks.forEach((t,i) => setTimeout(() => { try { t.click(); } catch (_) {} }, i*180));
              AudoibooMediaCapture.event('track-candidates='+tracks.length); return tracks.length;
            })();
        """.trimIndent()

        // Deliberately inspect only the active player DOM. Broad performance/HTML scans pulled
        // audio belonging to recommendations and other books on the page.
        private val SCAN_PLAYER = """
            (() => {
              const emit = u => { try { if (u) AudoibooMediaCapture.media(String(u)); } catch (_) {} };
              document.querySelectorAll('audio[src],audio source[src],source[src]').forEach(e => emit(e.src));
              return document.querySelectorAll('audio[src],audio source[src],source[src]').length;
            })();
        """.trimIndent()
    }
}
