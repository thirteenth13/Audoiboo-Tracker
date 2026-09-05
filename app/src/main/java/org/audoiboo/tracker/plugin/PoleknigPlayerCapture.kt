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
import org.json.JSONArray
import java.net.URI
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Poleknig exposes the complete player playlist as /books/<id>/playlist.txt. */
class PoleknigPlayerCapture(private val context: Context) {
    private companion object { const val BRIDGE = "AudoibooPoleCapture" }

    fun capture(manifest: PluginPackageManifest, rule: PluginMediaCaptureRule, pageUrl: String, onComplete: (PluginMediaCaptureResult) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            val found = LinkedHashSet<String>()
            val keys = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            var requests = 0

            fun isResolver(url: String): Boolean = runCatching {
                val uri = URI(url)
                val host = uri.host?.lowercase().orEmpty()
                val regex = rule.resolverPathRegex ?: return@runCatching false
                manifest.hosts.any { host == it || host.endsWith(".$it") } && regex.matches(uri.path.orEmpty())
            }.getOrDefault(false)

            fun remember(raw: String?, source: String) {
                val url = runCatching {
                    val v = raw?.trim()?.replace("\\/", "/").orEmpty()
                    if (v.isBlank()) return@runCatching null
                    URI(pageUrl).resolve(v).toString()
                }.getOrNull() ?: return
                if (!isResolver(url) && !PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, url)) return
                val key = PluginWebViewMediaCaptureRuntime.mediaKey(url) ?: url
                synchronized(found) {
                    if (keys.add(key) && found.size < rule.maxResults) {
                        found += url
                        if (diagnostics.size < 180) diagnostics += "accepted-$source:${url.take(500)}"
                    }
                }
            }

            fun snapshot(): List<String> {
                val all = synchronized(found) { found.toList() }
                val direct = all.filter { PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, it) }
                val selected = if (rule.preferDirectMedia && direct.isNotEmpty()) direct else all
                return if (rule.sortTrackNumber) selected.sortedWith(compareBy({ PluginWebViewMediaCaptureRuntime.trackNumber(it) ?: Int.MAX_VALUE }, { it })) else selected
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = snapshot()
                diagnostics += reason
                diagnostics += "pole-requests=$requests"
                diagnostics += "media=${media.size}"
                runCatching {
                    webView.stopLoading(); webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE); webView.destroy()
                }
                onComplete(PluginMediaCaptureResult(pageUrl, media, diagnostics.toList()))
            }

            fun parsePlaylist(raw: String?) {
                val text = raw?.trim()?.takeIf { it.isNotBlank() } ?: return
                runCatching {
                    val arr = JSONArray(text)
                    diagnostics += "pole-playlist-items=${arr.length()}"
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val title = item.optString("title", (i + 1).toString())
                        val file = item.optString("file").replace("\\/", "/")
                        // Some entries contain two equivalent signed resolver URLs separated by " or ".
                        // One valid URL is enough; prefer the first exactly as supplied by Poleknig.
                        val url = file.split(Regex("\\s+or\\s+"), limit = 2).firstOrNull()?.trim().orEmpty()
                        if (url.isNotBlank()) {
                            diagnostics += "pole-playlist-track:$title"
                            remember(url, "playlist")
                        }
                    }
                }.onFailure { diagnostics += "pole-playlist-parse-error:${it.javaClass.simpleName}" }
            }

            fun fetchPlaylist() {
                val script = """
                    (()=>{
                      const m=location.pathname.match(/^\/books\/(\d+)/);
                      if(!m){AudoibooPoleCapture.event('playlist-book-id-miss');return false;}
                      const u='/books/'+m[1]+'/playlist.txt?t='+Date.now();
                      AudoibooPoleCapture.event('playlist-fetch:'+u);
                      fetch(u,{credentials:'include',cache:'no-store'})
                        .then(r=>{AudoibooPoleCapture.event('playlist-http:'+r.status);if(!r.ok)throw new Error('HTTP '+r.status);return r.text()})
                        .then(t=>AudoibooPoleCapture.playlist(t))
                        .catch(e=>AudoibooPoleCapture.event('playlist-error:'+String(e)));
                      return true;
                    })()
                """.trimIndent()
                webView.evaluateJavascript(script) { raw ->
                    if (raw != "true") diagnostics += "pole-playlist-start-miss"
                }
            }

            @SuppressLint("SetJavaScriptEnabled")
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = userAgentString.replace("; wv", "")
            }
            webView.addJavascriptInterface(object {
                @JavascriptInterface fun playlist(value: String?) = handler.post {
                    parsePlaylist(value)
                    handler.postDelayed({ finish("pole-playlist-complete") }, 150L)
                }
                @JavascriptInterface fun event(value: String?) = handler.post {
                    if (!value.isNullOrBlank() && diagnostics.size < 240) diagnostics += "js:$value"
                }
            }, BRIDGE)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    requests++
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url) || finished.get()) return
                    diagnostics += "pole-loaded"
                    handler.postDelayed({ fetchPlaylist() }, 250L)
                }
            }
            handler.postDelayed({ finish("pole-playlist-timeout") }, minOf(rule.timeoutMs + 2_000L, 14_000L))
            webView.loadUrl(pageUrl)
        }
    }
}
