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

/** Device-WebView traversal for Poleknig's custom player. */
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
            var clicks = 0
            var requests = 0

            fun isResolver(url: String): Boolean = runCatching {
                val uri = URI(url); val host = uri.host?.lowercase().orEmpty(); val regex = rule.resolverPathRegex ?: return@runCatching false
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
                        if (diagnostics.size < 160) diagnostics += "accepted-$source:${url.take(500)}"
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
                diagnostics += "pole-js-clicks=$clicks"
                diagnostics += "pole-requests=$requests"
                diagnostics += "media=${media.size}"
                runCatching {
                    webView.stopLoading(); webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE); webView.destroy()
                }
                onComplete(PluginMediaCaptureResult(pageUrl, media, diagnostics.toList()))
            }

            fun scan() {
                webView.evaluateJavascript(
                    """(()=>{const emit=u=>{try{if(u)AudoibooPoleCapture.media(new URL(String(u),location.href).href)}catch(_){}};document.querySelectorAll('audio,source,a[href],[data-src],[data-url],[data-file],[data-audio]').forEach(e=>{['src','href','data-src','data-url','data-file','data-audio'].forEach(a=>emit(e.getAttribute&&e.getAttribute(a)));emit(e.currentSrc)});try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){};const h=document.documentElement.outerHTML.replaceAll('\\/','/');const m=h.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+(?:\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\/files\/\d+)(?:\?[^\"'<>\s]*)?/gi)||[];m.slice(0,500).forEach(emit);return m.length})()""",
                    null
                )
            }

            // This mirrors the browser experiment that proved Poleknig traversal works: select the
            // smallest exact 01..NN element, climb only to a genuinely interactive ancestor, then
            // dispatch mousedown/mouseup/click in JavaScript. Native taps on PJSDIV did not change
            // the player state in Android WebView (CI 852).
            fun clickTrackScript(number: Int): String {
                val label = number.toString().padStart(2, '0')
                return """
                    (()=>{
                      const label='$label',norm=s=>String(s||'').replace(/\s+/g,' ').trim();
                      let a=[...document.querySelectorAll('body *')].filter(e=>norm(e.innerText||e.textContent)===label);
                      a=a.filter(e=>![...e.children].some(c=>norm(c.innerText||c.textContent)===label));
                      a=a.filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>3&&r.height>3&&r.height<120&&s.display!=='none'&&s.visibility!=='hidden'});
                      a.sort((x,y)=>{const A=x.getBoundingClientRect(),B=y.getBoundingClientRect();return A.width*A.height-B.width*B.height});
                      if(!a.length){AudoibooPoleCapture.event('track-miss:'+label);return false;}
                      const leaf=a[0];
                      const tap=leaf.closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]')||leaf;
                      tap.scrollIntoView({block:'center'});
                      AudoibooPoleCapture.event('track:'+label+':leaf='+leaf.tagName+'/'+String(leaf.className||'').slice(0,45)+':tap='+tap.tagName+'/'+String(tap.className||'').slice(0,45));
                      try{
                        tap.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,view:window}));
                        tap.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,view:window}));
                        tap.click();
                        AudoibooPoleCapture.event('track-js-click:'+label);
                        return true;
                      }catch(e){AudoibooPoleCapture.event('track-js-error:'+label+':'+String(e));return false;}
                    })()
                """.trimIndent()
            }

            fun traverse(number: Int = 1, misses: Int = 0) {
                if (finished.get()) return
                if (number > 60 || misses >= 3) {
                    diagnostics += "pole-track-stop:number=$number misses=$misses"
                    handler.postDelayed({ scan(); finish("pole-complete") }, 900L)
                    return
                }
                webView.evaluateJavascript(clickTrackScript(number)) { raw ->
                    val ok = raw == "true"
                    if (!ok) {
                        handler.postDelayed({ traverse(number + 1, misses + 1) }, 180L)
                    } else {
                        clicks++
                        handler.postDelayed({
                            scan()
                            traverse(number + 1, 0)
                        }, 650L)
                    }
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
                @JavascriptInterface fun media(value: String?) = handler.post { remember(value, "js") }
                @JavascriptInterface fun event(value: String?) = handler.post {
                    if (!value.isNullOrBlank() && diagnostics.size < 200) diagnostics += "js:$value"
                }
            }, BRIDGE)
            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    requests++
                    remember(request?.url?.toString(), "network")
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url) || finished.get()) return
                    diagnostics += "pole-loaded"
                    handler.postDelayed({ scan(); traverse() }, 900L)
                }
            }
            handler.postDelayed({ finish("pole-timeout") }, minOf(rule.timeoutMs + 8_000L, 32_000L))
            webView.loadUrl(pageUrl)
        }
    }
}
