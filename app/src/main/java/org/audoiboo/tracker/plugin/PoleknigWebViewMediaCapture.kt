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
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Device-side resolution for Poleknig's dynamic /files/<id> player endpoints. */
class PoleknigWebViewMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 20_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Poleknig URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedMap(LinkedHashMap<String, String>())
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val url = normalizeUrl(raw) ?: return
                if (!isResolverUrl(url) && !isDirectAudio(url)) return
                synchronized(found) { if (found.size < 250) found.putIfAbsent(mediaKey(url), url) }
            }
            fun snapshot(): List<String> = synchronized(found) { found.values.toList() }
            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                val media = selectResolvedMedia(snapshot())
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
                    if (!message.isNullOrBlank() && diagnostics.size < 120) diagnostics += "js:$message"
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
                    listOf(700L, 2_000L, 4_000L, 7_000L, 10_000L, 13_000L, 16_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(ACTIVATE_AND_SCAN, null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("playlist-scan-complete") }, 18_000L)
                }
            }
            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(20_000L))
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooPoleCapture"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "m3u8")

        fun normalizeUrl(raw: String?): String? {
            val value = raw?.trim()?.replace("&amp;", "&")?.replace("\\/", "/").orEmpty()
            return value.takeIf { it.isNotBlank() }
        }
        private fun mediaKey(url: String): String = runCatching {
            val u = URI(url); "${u.scheme?.lowercase()}://${u.host?.lowercase()}${u.path}?${u.rawQuery.orEmpty()}"
        }.getOrDefault(url)
        fun isAllowedPage(url: String): Boolean = runCatching {
            val uri = URI(url.trim()); val host = uri.host?.lowercase().orEmpty()
            uri.scheme?.lowercase() in setOf("http", "https") && (host == "poleknig.com" || host.endsWith(".poleknig.com")) && uri.path.orEmpty().startsWith("/books/")
        }.getOrDefault(false)
        fun isResolverUrl(url: String): Boolean = runCatching {
            val uri = URI(url.trim()); val host = uri.host?.lowercase().orEmpty(); val path = uri.path.orEmpty().lowercase()
            uri.scheme?.lowercase() in setOf("http", "https") && (host == "poleknig.com" || host.endsWith(".poleknig.com")) && Regex("""^/files/\d+/?$""").matches(path)
        }.getOrDefault(false)
        fun isDirectAudio(url: String): Boolean = runCatching {
            val uri = URI(url.trim()); uri.scheme?.lowercase() in setOf("http", "https") && uri.path.orEmpty().lowercase().substringAfterLast('.', "") in AUDIO_EXTENSIONS
        }.getOrDefault(false)
        fun isResolverOrAudio(url: String): Boolean = isResolverUrl(url) || isDirectAudio(url)
        fun selectResolvedMedia(urls: List<String>): List<String> {
            val distinct = urls.mapNotNull(::normalizeUrl).distinctBy(::mediaKey)
            val audio = distinct.filter(::isDirectAudio)
            return if (audio.isNotEmpty()) audio else distinct.filter(::isResolverUrl)
        }

        private val INSTALL_HOOKS = """
            (() => {
              if (window.__audoibooPoleHooks) return 'already'; window.__audoibooPoleHooks = true;
              const emit=u=>{try{if(u)AudoibooPoleCapture.media(String(u).replaceAll('&amp;','&'));}catch(_){}};
              const scanText=t=>{try{String(t||'').replaceAll('\\/','/').match(/(?:https?:\/\/[^\"'<>\s]+)?\/files\/\d+(?:\?[^\"'<>\s]*)?/gi)?.forEach(u=>emit(new URL(u,location.href).href));}catch(_){}};
              const oldFetch=window.fetch; if(oldFetch) window.fetch=function(...args){args.forEach(scanText); return oldFetch.apply(this,args).then(r=>{try{scanText(r.url);}catch(_){} return r;});};
              const oldOpen=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){emit(u);return oldOpen.apply(this,arguments);};
              const src=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src'); if(src?.set) Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:src.configurable,enumerable:src.enumerable,get:src.get,set(v){emit(v);return src.set.call(this,v);}});
              return 'installed';
            })();
        """.trimIndent()

        private val ACTIVATE_AND_SCAN = """
            (() => {
              const emit=u=>{try{if(u)AudoibooPoleCapture.media(String(u).replaceAll('&amp;','&'));}catch(_){}};
              const norm=s=>String(s||'').replace(/\s+/g,' ').trim();
              const roots=()=>{const out=[],seen=new Set();const add=r=>{if(!r||seen.has(r))return;seen.add(r);out.push(r);try{r.querySelectorAll('*').forEach(e=>{if(e.shadowRoot)add(e.shadowRoot)});r.querySelectorAll('iframe,frame').forEach(f=>{try{if(f.contentDocument)add(f.contentDocument)}catch(_){}})}catch(_){}};add(document);return out;};
              const candidates=[];
              roots().forEach(r=>{try{
                r.querySelectorAll('audio[src],source[src],a[href]').forEach(e=>emit(e.currentSrc||e.src||e.href));
                r.querySelectorAll('script').forEach(s=>{const t=String(s.textContent||s.innerHTML||'').replaceAll('\\/','/');(t.match(/(?:https?:\/\/[^\"'<>\s]+)?\/files\/\d+(?:\?[^\"'<>\s]*)?/gi)||[]).forEach(u=>emit(new URL(u,location.href).href));});
                r.querySelectorAll('button,a,div,span,li').forEach(e=>{const t=norm(e.innerText||e.textContent);if(/^0?\d{1,2}$/.test(t)&&Number(t)>=1&&Number(t)<=99)candidates.push(e);});
              }catch(_){}});
              const unique=[...new Set(candidates)].sort((a,b)=>Number(norm(a.innerText||a.textContent))-Number(norm(b.innerText||b.textContent))).slice(0,120);
              unique.forEach((e,i)=>setTimeout(()=>{try{e.scrollIntoView({block:'center'});e.click();}catch(_){}},i*180));
              try{performance.getEntriesByType('resource').forEach(e=>emit(e.name));}catch(_){}
              AudoibooPoleCapture.event('playlist-candidates='+unique.length); return unique.length;
            })();
        """.trimIndent()
    }
}
