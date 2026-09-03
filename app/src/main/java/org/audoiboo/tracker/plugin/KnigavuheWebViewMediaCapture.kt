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
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Device-side media discovery for Knigavuhe pages. */
class KnigavuheWebViewMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 20_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Knigavuhe URL" }
        Handler(Looper.getMainLooper()).post {
            val found = Collections.synchronizedMap(LinkedHashMap<String, String>())
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val armed = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                if (!armed.get()) return
                val url = raw?.trim()?.replace("&amp;", "&").orEmpty()
                if (!isBookAudio(url)) return
                val key = logicalTrackKey(url) ?: return
                synchronized(found) {
                    if (found.size < MAX_MEDIA_URLS) found.putIfAbsent(key, url)
                }
            }
            fun snapshot(): List<String> = synchronized(found) {
                found.values.toList().sortedWith(compareBy({ trackNumber(it) ?: Int.MAX_VALUE }, { it }))
            }
            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                val media = snapshot()
                diagnostics += reason
                diagnostics += "logicalTracks=${media.size}"
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
                    if (!message.isNullOrBlank() && diagnostics.size < 160) diagnostics += "js:$message"
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
                    diagnostics += "loaded:${Uri.parse(url).host}"
                    view.evaluateJavascript(INSTALL_HOOKS, null)
                    handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(PREPARE_PLAYER, null) }, 700L)
                    handler.postDelayed({
                        if (!finished.get()) {
                            synchronized(found) { found.clear() }
                            armed.set(true)
                            diagnostics += "capture-armed"
                            view.evaluateJavascript(WALK_VISIBLE_PLAYLIST, null)
                        }
                    }, 1_700L)
                    handler.postDelayed({ if (!finished.get() && snapshot().isNotEmpty()) finish("captured-visible-playlist") }, 18_500L)
                }
            }
            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(20_000L))
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooMediaCapture"
        private const val MAX_MEDIA_URLS = 80
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "m3u8")

        fun isAllowedPage(url: String): Boolean = runCatching {
            val u = URI(url); val h = u.host?.lowercase().orEmpty()
            u.scheme?.lowercase() in setOf("http", "https") && (h == "knigavuhe.org" || h.endsWith(".knigavuhe.org"))
        }.getOrDefault(false)

        fun isBookAudio(url: String): Boolean = runCatching {
            val u = URI(url); val h = u.host?.lowercase().orEmpty(); val p = u.path?.lowercase().orEmpty()
            u.scheme?.lowercase() in setOf("http", "https") &&
                (h == "knigavuhe.org" || h.endsWith(".knigavuhe.org")) &&
                p.contains("/audio/") && p.substringAfterLast('.', "") in AUDIO_EXTENSIONS
        }.getOrDefault(false)

        fun logicalTrackKey(url: String): String? = runCatching {
            val file = URI(url).path.substringAfterLast('/').lowercase()
            val number = Regex("(?:track[-_ ]?)?(\\d{1,3})(?:-1)?\\.[a-z0-9]+$").find(file)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (number != null) "track/$number" else file
        }.getOrNull()

        fun trackNumber(url: String): Int? = runCatching {
            val file = URI(url).path.substringAfterLast('/').substringBeforeLast('.')
            Regex("(?<!\\d)(\\d{1,3})(?:-1)?(?!\\d)").findAll(file)
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .lastOrNull()
        }.getOrNull()

        fun isLikelyTrackLabel(text: String): Boolean = text.trim().length in 1..180 &&
            Regex("""(?ix)^\s*(?:\d{1,3}(?:[\s._:)-]+.+)?|.+_\d+)(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?\s*$""").matches(text.trim())

        private val INSTALL_HOOKS = """
            (()=>{
              if(window.__audoibooHooks)return'already'; window.__audoibooHooks=true;
              const emit=u=>{try{if(u)AudoibooMediaCapture.media(String(u))}catch(_){}};
              const f=window.fetch;if(f)window.fetch=function(...a){a.forEach(x=>emit(typeof x==='string'?x:x?.url));return f.apply(this,a)};
              const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){emit(u);return o.apply(this,arguments)};
              const s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s?.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);return s.set.call(this,v)}});
              return'installed';
            })();
        """.trimIndent()

        private val PREPARE_PLAYER = """
            (()=>{
              const n=s=>String(s||'').replace(/\s+/g,' ').trim();
              const all=[...document.querySelectorAll('button,a,div,span,li,label,input')];
              const large=all.find(e=>/большие отрезки/i.test(n(e.innerText||e.value)));
              let c=large?.matches?.('input[type=checkbox]')?large:large?.querySelector?.('input[type=checkbox]');
              if(!c)c=document.querySelector('input[type=checkbox][name*=large i],input[type=checkbox][id*=large i]');
              if(c&&!c.checked){try{c.click();c.dispatchEvent(new Event('change',{bubbles:true}));AudoibooMediaCapture.event('enabled-large-segments')}catch(_){}}
              else AudoibooMediaCapture.event(c?'large-segments-already-enabled':'large-segments-switch-not-found');
              return true;
            })();
        """.trimIndent()

        private val WALK_VISIBLE_PLAYLIST = """
            (()=>{
              if(window.__audoibooKnigaWalk)return'already';window.__audoibooKnigaWalk=true;
              const norm=s=>String(s||'').replace(/\s+/g,' ').trim();
              const rows=[...document.querySelectorAll('body *')].filter(e=>{
                const t=norm(e.innerText||e.textContent);
                if(!/^.+_\d+$/.test(t))return false;
                return ![...e.children].some(c=>norm(c.innerText||c.textContent)===t);
              });
              const uniq=[];const seen=new Set();
              rows.forEach(e=>{const t=norm(e.innerText||e.textContent);if(!seen.has(t)){seen.add(t);uniq.push(e)}});
              uniq.sort((a,b)=>{const na=Number(norm(a.innerText||a.textContent).match(/_(\d+)$/)?.[1]||999),nb=Number(norm(b.innerText||b.textContent).match(/_(\d+)$/)?.[1]||999);return na-nb});
              AudoibooMediaCapture.event('visible-tracks='+uniq.length);
              let i=0;
              const step=()=>{
                if(i>=uniq.length){window.__audoibooKnigaWalk=false;AudoibooMediaCapture.event('walk-done='+i);return;}
                const e=uniq[i++];const row=e.closest('li,[role=button],[onclick],div')||e;
                try{row.scrollIntoView({block:'center'});row.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true}));row.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true}));row.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true}));row.dispatchEvent(new PointerEvent('pointerup',{bubbles:true}));row.click();}catch(_){}
                setTimeout(step,260);
              };
              step();return uniq.length;
            })();
        """.trimIndent()
    }
}
