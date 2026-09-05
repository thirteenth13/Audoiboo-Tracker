package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.net.URI
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Exact mobile Knigavuhe traversal, with fallback to the site's short numeric segments. */
class KnigavuhePlayerCapture(private val context: Context) {
    private companion object { const val BRIDGE = "AudoibooKvCapture" }

    fun capture(manifest: PluginPackageManifest, rule: PluginMediaCaptureRule, pageUrl: String, onComplete: (PluginMediaCaptureResult) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            val found = LinkedHashSet<String>()
            val keys = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val armed = AtomicBoolean(false)
            var clicks = 0
            var requests = 0

            fun remember(raw: String?, source: String) {
                if (!armed.get()) return
                val url = runCatching {
                    val v = raw?.trim()?.replace("\\/", "/").orEmpty()
                    if (v.isBlank()) return@runCatching null
                    URI(pageUrl).resolve(v).toString()
                }.getOrNull() ?: return
                if (!PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, url)) return
                val key = PluginWebViewMediaCaptureRuntime.mediaKey(url) ?: url
                synchronized(found) {
                    if (keys.add(key) && found.size < rule.maxResults) {
                        found += url
                        if (diagnostics.size < 180) diagnostics += "accepted-$source:${url.take(500)}"
                    }
                }
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = synchronized(found) { found.toList() }.let {
                    if (rule.sortTrackNumber) it.sortedWith(compareBy({ PluginWebViewMediaCaptureRuntime.trackNumber(it) ?: Int.MAX_VALUE }, { it })) else it
                }
                diagnostics += reason
                diagnostics += "kv-native-clicks=$clicks"
                diagnostics += "kv-requests=$requests"
                diagnostics += "media=${media.size}"
                runCatching {
                    webView.stopLoading(); webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE); webView.destroy()
                }
                onComplete(PluginMediaCaptureResult(pageUrl, media, diagnostics.toList()))
            }

            fun tap(x: Float, y: Float) {
                val now = SystemClock.uptimeMillis()
                val d = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val u = MotionEvent.obtain(now, now + 55, MotionEvent.ACTION_UP, x, y, 0)
                webView.dispatchTouchEvent(d); webView.dispatchTouchEvent(u)
                d.recycle(); u.recycle(); clicks++
            }

            fun rect(raw: String?): Pair<Float, Float>? = runCatching {
                val t = raw?.takeIf { it != "null" } ?: return@runCatching null
                val o = JSONObject(t)
                o.optDouble("x").toFloat() to o.optDouble("y").toFloat()
            }.getOrNull()

            fun scan() {
                if (!armed.get()) return
                webView.evaluateJavascript(
                    """(()=>{const emit=u=>{try{if(u)AudoibooKvCapture.media(new URL(String(u),location.href).href)}catch(_){}};document.querySelectorAll('audio,source').forEach(e=>{emit(e.currentSrc);emit(e.src);emit(e.getAttribute&&e.getAttribute('src'))});try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){};return true})()""",
                    null
                )
            }

            fun installHooks() {
                webView.evaluateJavascript(
                    """(()=>{if(window.__audoibooKvHooks)return true;window.__audoibooKvHooks=true;const emit=u=>{try{if(u)AudoibooKvCapture.media(new URL(String(u),location.href).href)}catch(_){}};try{const s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s&&s.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);return s.set.call(this,v)}})}catch(_){};try{const A=window.Audio;if(A){window.Audio=function(src){const a=new A(src);emit(src);return a};window.Audio.prototype=A.prototype}}catch(_){};return true})()""",
                    null
                )
            }

            fun controlScript(regex: String, tag: String): String = """
                (()=>{
                  const re=$regex,n=s=>String(s||'').replace(/\s+/g,' ').trim();
                  let a=[...document.querySelectorAll('button,a,label,div,span,input,[role=button]')].filter(e=>re.test(n(e.innerText||e.textContent||e.value||e.getAttribute?.('aria-label')||'')));
                  a=a.filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>8&&r.height>8&&s.display!=='none'&&s.visibility!=='hidden'});
                  a.sort((x,y)=>{const A=x.getBoundingClientRect(),B=y.getBoundingClientRect();return A.width*A.height-B.width*B.height});
                  if(!a.length){AudoibooKvCapture.event('$tag-miss');return null;}
                  const e=a[0],c=e.closest('button,a,label,[role=button],[onclick]')||e;
                  c.scrollIntoView({block:'center'});const r=c.getBoundingClientRect();
                  AudoibooKvCapture.event('$tag-found:'+n(e.innerText||e.textContent||e.value||'').slice(0,80));
                  return{x:r.left+r.width/2,y:r.top+r.height/2};
                })()
            """.trimIndent()

            fun longRowScript(index: Int): String = """
                (()=>{
                  const idx=$index,n=s=>String(s||'').replace(/\s+/g,' ').trim();
                  const match=t=>{const m=n(t).match(/_(\d+)(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?$/);return !!m&&Number(m[1])===idx};
                  let a=[...document.querySelectorAll('body *')].filter(e=>match(e.innerText||e.textContent));
                  a=a.filter(e=>![...e.children].some(c=>match(c.innerText||c.textContent)));
                  a=a.filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>3&&r.height>3&&r.height<180&&s.display!=='none'&&s.visibility!=='hidden'});
                  a.sort((x,y)=>{const A=x.getBoundingClientRect(),B=y.getBoundingClientRect();return A.width*A.height-B.width*B.height});
                  if(!a.length){AudoibooKvCapture.event('row-miss:'+idx);return null;}
                  const e=a[0],c=e.closest('button,a,[role=button],[onclick],li,[class*=track],[class*=item]')||e;
                  c.scrollIntoView({block:'center'});const r=c.getBoundingClientRect();
                  AudoibooKvCapture.event('row:'+idx+':'+n(e.innerText||e.textContent).slice(0,100));
                  return{x:r.left+Math.min(Math.max(18,r.width*.12),r.width/2),y:r.top+r.height/2};
                })()
            """.trimIndent()

            fun shortRowScript(index: Int): String {
                val label = index.toString().padStart(2, '0')
                return """
                    (()=>{
                      const label='$label',n=s=>String(s||'').replace(/\s+/g,' ').trim();
                      let a=[...document.querySelectorAll('body *')].filter(e=>n(e.innerText||e.textContent)===label);
                      a=a.filter(e=>![...e.children].some(c=>n(c.innerText||c.textContent)===label));
                      a=a.filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>3&&r.height>3&&r.height<120&&s.display!=='none'&&s.visibility!=='hidden'});
                      a.sort((x,y)=>{const A=x.getBoundingClientRect(),B=y.getBoundingClientRect();return A.width*A.height-B.width*B.height});
                      if(!a.length){AudoibooKvCapture.event('short-miss:'+label);return null;}
                      const e=a[0],c=e.closest('button,a,[role=button],[onclick],li,[class*=track],[class*=item]')||e;
                      c.scrollIntoView({block:'center'});const r=c.getBoundingClientRect();
                      AudoibooKvCapture.event('short-row:'+label+':'+e.tagName+'/'+String(e.className||'').slice(0,60));
                      return{x:r.left+r.width/2,y:r.top+r.height/2};
                    })()
                """.trimIndent()
            }

            fun traverseShort(index: Int = 0, misses: Int = 0) {
                if (finished.get()) return
                if (index >= 100 || misses >= 4) {
                    diagnostics += "kv-short-stop:index=$index misses=$misses"
                    handler.postDelayed({ scan(); finish("kv-complete-short") }, 900L)
                    return
                }
                webView.evaluateJavascript(shortRowScript(index)) { raw ->
                    val p = rect(raw)
                    if (p == null) {
                        handler.postDelayed({ traverseShort(index + 1, misses + 1) }, 150L)
                    } else {
                        tap(p.first, p.second)
                        handler.postDelayed({ scan(); traverseShort(index + 1, 0) }, 420L)
                    }
                }
            }

            fun traverseLong(index: Int = 0, misses: Int = 0, waits: Int = 0) {
                if (finished.get()) return
                if (index >= 90 || misses >= 4) {
                    diagnostics += "kv-track-stop:index=$index misses=$misses"
                    diagnostics += "kv-fallback-short"
                    traverseShort()
                    return
                }
                webView.evaluateJavascript(longRowScript(index)) { raw ->
                    val p = rect(raw)
                    if (p == null) {
                        if (index == 0 && waits < 10) {
                            diagnostics += "kv-row-wait=${waits + 1}"
                            handler.postDelayed({ traverseLong(0, 0, waits + 1) }, 500L)
                        } else if (index == 0) {
                            diagnostics += "kv-long-rows-unavailable"
                            traverseShort()
                        } else {
                            handler.postDelayed({ traverseLong(index + 1, misses + 1, waits) }, 150L)
                        }
                    } else {
                        tap(p.first, p.second)
                        handler.postDelayed({ scan(); traverseLong(index + 1, 0, waits) }, 420L)
                    }
                }
            }

            fun armAndTraverse() {
                synchronized(found) { found.clear(); keys.clear() }
                armed.set(true)
                diagnostics += "kv-armed"
                scan()
                traverseLong()
            }

            fun prepareControls() {
                webView.evaluateJavascript(controlScript("/слушать\\s+полностью/i", "full-player")) { fullRaw ->
                    rect(fullRaw)?.let {
                        tap(it.first, it.second)
                        diagnostics += "kv-full-player-native"
                    } ?: run { diagnostics += "kv-full-player-miss" }
                    handler.postDelayed({
                        webView.evaluateJavascript(controlScript("/большие\\s+отрезки/i", "toggle-long")) { toggleRaw ->
                            rect(toggleRaw)?.let {
                                tap(it.first, it.second)
                                diagnostics += "kv-toggle-long-native"
                            } ?: run { diagnostics += "kv-toggle-long-miss" }
                            handler.postDelayed({ armAndTraverse() }, 1200L)
                        }
                    }, 900L)
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
                    if (!value.isNullOrBlank() && diagnostics.size < 220) diagnostics += "js:$value"
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
                    diagnostics += "kv-loaded"
                    installHooks()
                    handler.postDelayed({ prepareControls() }, 1200L)
                }
            }
            handler.postDelayed({ finish("kv-timeout") }, minOf(rule.timeoutMs + 12_000L, 36_000L))
            webView.loadUrl(pageUrl)
        }
    }
}
