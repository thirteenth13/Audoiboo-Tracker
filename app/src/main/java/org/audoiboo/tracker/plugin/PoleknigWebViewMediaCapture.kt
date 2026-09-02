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

    fun capture(pageUrl: String, timeoutMs: Long = 24_000L, onComplete: (Result) -> Unit) {
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
                    if (!message.isNullOrBlank() && diagnostics.size < 180) diagnostics += "js:$message"
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onLoadResource(view: WebView?, url: String?) {
                    remember(url)
                    super.onLoadResource(view, url)
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!isAllowedPage(url)) return
                    diagnostics += "loaded"
                    view.evaluateJavascript(INSTALL_HOOKS, null)
                    handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(WALK_PLAYLIST, null) }, 800L)
                    listOf(5_000L, 10_000L, 15_000L, 20_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(SCAN_ONLY, null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("playlist-walk-complete") }, 22_000L)
                }
            }
            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(24_000L))
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
            uri.scheme?.lowercase() in setOf("http", "https") &&
                (host == "poleknig.com" || host.endsWith(".poleknig.com")) &&
                uri.path.orEmpty().startsWith("/books/")
        }.getOrDefault(false)
        fun isResolverUrl(url: String): Boolean = runCatching {
            val uri = URI(url.trim()); val host = uri.host?.lowercase().orEmpty(); val path = uri.path.orEmpty().lowercase()
            uri.scheme?.lowercase() in setOf("http", "https") &&
                (host == "poleknig.com" || host.endsWith(".poleknig.com")) &&
                Regex("""^/files/\d+/?$""").matches(path)
        }.getOrDefault(false)
        fun isDirectAudio(url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            uri.scheme?.lowercase() in setOf("http", "https") &&
                uri.path.orEmpty().lowercase().substringAfterLast('.', "") in AUDIO_EXTENSIONS
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
              const scan=t=>{try{const x=String(t||'').replaceAll('\\/','/');(x.match(/(?:https?:\/\/[^\"'<>\s]+)?\/files\/\d+(?:\?[^\"'<>\s]*)?/gi)||[]).forEach(u=>emit(new URL(u,location.href).href));(x.match(/https?:[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\?[^\"'<>\s]*)?/gi)||[]).forEach(emit);}catch(_){}};
              const oldFetch=window.fetch;if(oldFetch)window.fetch=function(...args){args.forEach(scan);return oldFetch.apply(this,args).then(r=>{scan(r.url);return r;});};
              const oldOpen=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){emit(u);return oldOpen.apply(this,arguments);};
              const src=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(src?.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:src.configurable,enumerable:src.enumerable,get:src.get,set(v){emit(v);return src.set.call(this,v);}});
              return 'installed';
            })();
        """.trimIndent()

        private val SCAN_ONLY = """
            (()=>{
              const emit=u=>{try{if(u)AudoibooPoleCapture.media(String(u).replaceAll('&amp;','&'));}catch(_){}};
              document.querySelectorAll('audio[src],source[src],a[href]').forEach(e=>emit(e.currentSrc||e.src||e.href));
              try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){}
              const html=document.documentElement.innerHTML.replaceAll('\\/','/');
              (html.match(/(?:https?:\/\/[^\"'<>\s]+)?\/files\/\d+(?:\?[^\"'<>\s]*)?/gi)||[]).forEach(u=>emit(new URL(u,location.href).href));
              return true;
            })();
        """.trimIndent()

        private val WALK_PLAYLIST = """
            (()=>{
              if(window.__audoibooPoleWalkRunning)return'already';window.__audoibooPoleWalkRunning=true;
              const emit=u=>{try{if(u)AudoibooPoleCapture.media(String(u).replaceAll('&amp;','&'));}catch(_){}};
              const norm=s=>String(s||'').replace(/\s+/g,' ').trim();
              const scan=()=>{document.querySelectorAll('audio[src],source[src],a[href]').forEach(e=>emit(e.currentSrc||e.src||e.href));try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){}};
              const exact=label=>[...document.querySelectorAll('body *')].filter(e=>norm(e.innerText||e.textContent)===label&&!Array.from(e.children||[]).some(c=>norm(c.innerText||c.textContent)===label)).sort((a,b)=>{const ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return ar.width*ar.height-br.width*br.height})[0]||null;
              const clickable=e=>e?.closest?.('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]')||e;
              const scrollHost=e=>{let p=e;for(let i=0;i<12&&p;i++,p=p.parentElement){try{const s=getComputedStyle(p);if(p.scrollHeight>p.clientHeight+30&&/(auto|scroll)/.test(s.overflowY))return p}catch(_){}}return null};
              let number=1,misses=0,host=null,clicks=0;
              const step=()=>{
                if(number>99||misses>=8){scan();AudoibooPoleCapture.event('walk-done clicks='+clicks+' last='+(number-1));window.__audoibooPoleWalkRunning=false;return;}
                const label=String(number).padStart(2,'0');let el=exact(label);
                if(!el){
                  if(!host){const any=exact(String(Math.max(1,number-1)).padStart(2,'0'))||exact('01');host=scrollHost(any)}
                  if(host){try{host.scrollTop=Math.min(host.scrollHeight,host.scrollTop+Math.max(80,host.clientHeight*.75));host.dispatchEvent(new Event('scroll',{bubbles:true}))}catch(_){}}
                  misses++;setTimeout(step,180);return;
                }
                misses=0;host=host||scrollHost(el);const target=clickable(el);
                try{target.scrollIntoView({block:'center'});target.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,view:window}));target.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,view:window}));target.click();clicks++;}catch(_){}
                scan();number++;setTimeout(step,220);
              };
              step();AudoibooPoleCapture.event('walk-start');return true;
            })();
        """.trimIndent()
    }
}
