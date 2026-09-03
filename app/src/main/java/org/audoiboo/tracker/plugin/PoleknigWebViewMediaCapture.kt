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

    fun capture(pageUrl: String, timeoutMs: Long = 26_000L, onComplete: (Result) -> Unit) {
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
                    if (!message.isNullOrBlank() && diagnostics.size < 220) diagnostics += "js:$message"
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
                    handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(WALK_PLAYLIST, null) }, 900L)
                    listOf(6_000L, 12_000L, 18_000L, 23_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(SCAN_ONLY, null) }, delay)
                    }
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("playlist-walk-complete") }, 24_500L)
                }
            }
            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(26_000L))
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
                (host == "poleknig.com" || host.endsWith(".poleknig.com")) && uri.path.orEmpty().startsWith("/books/")
        }.getOrDefault(false)
        fun isResolverUrl(url: String): Boolean = runCatching {
            val uri = URI(url.trim()); val host = uri.host?.lowercase().orEmpty(); val path = uri.path.orEmpty().lowercase()
            uri.scheme?.lowercase() in setOf("http", "https") &&
                (host == "poleknig.com" || host.endsWith(".poleknig.com")) && Regex("""^/files/\d+/?$""").matches(path)
        }.getOrDefault(false)
        fun isDirectAudio(url: String): Boolean = runCatching {
            val uri = URI(url.trim())
            uri.scheme?.lowercase() in setOf("http", "https") && uri.path.orEmpty().lowercase().substringAfterLast('.', "") in AUDIO_EXTENSIONS
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
              return true;
            })();
        """.trimIndent()

        private val WALK_PLAYLIST = """
            (()=>{
              if(window.__audoibooPoleWalkRunning)return'already';window.__audoibooPoleWalkRunning=true;
              const emit=u=>{try{if(u)AudoibooPoleCapture.media(String(u).replaceAll('&amp;','&'));}catch(_){}};
              const norm=s=>String(s||'').replace(/\s+/g,' ').trim();
              const scan=()=>{document.querySelectorAll('audio[src],source[src],a[href]').forEach(e=>emit(e.currentSrc||e.src||e.href));try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){}};
              const leafFor=label=>[...document.querySelectorAll('body *')].filter(e=>norm(e.innerText||e.textContent)===label&&!Array.from(e.children||[]).some(c=>norm(c.innerText||c.textContent)===label)).sort((a,b)=>{const ar=a.getBoundingClientRect(),br=b.getBoundingClientRect();return ar.width*ar.height-br.width*br.height})[0]||null;
              const rowFor=e=>e?.closest?.('li,[role=listitem],tr,.track,.item,[data-track],[data-file]')||e?.parentElement||e;
              const targets=e=>{const row=rowFor(e);const list=[];if(row){list.push(...row.querySelectorAll('button,a,[role=button],[onclick],[data-track],[data-file],svg,path'));list.push(row)}if(e)list.push(e);return [...new Set(list)]};
              const fire=t=>{try{t.scrollIntoView({block:'center'});['pointerdown','mousedown','touchstart','pointerup','mouseup','touchend'].forEach(type=>{try{const ev=type.startsWith('touch')?new Event(type,{bubbles:true,cancelable:true}):type.startsWith('pointer')?new PointerEvent(type,{bubbles:true,cancelable:true}):new MouseEvent(type,{bubbles:true,cancelable:true,view:window});t.dispatchEvent(ev)}catch(_){}});try{t.click()}catch(_){};return true}catch(_){return false}};
              let n=1,clicks=0,misses=0;
              const step=()=>{
                if(n>60||misses>=5){scan();AudoibooPoleCapture.event('walk-done clicks='+clicks+' last='+(n-1));window.__audoibooPoleWalkRunning=false;return;}
                const label=String(n).padStart(2,'0'),leaf=leafFor(label);
                if(!leaf){misses++;n++;setTimeout(step,250);return;}
                misses=0;const ts=targets(leaf);let fired=0;ts.slice(0,4).forEach((t,i)=>setTimeout(()=>{if(fire(t))fired++;scan()},i*90));
                clicks++;AudoibooPoleCapture.event('track-'+label+' targets='+Math.min(ts.length,4));n++;setTimeout(step,520);
              };
              step();AudoibooPoleCapture.event('walk-start');return true;
            })();
        """.trimIndent()
    }
}
