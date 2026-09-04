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

/**
 * Device-WebView traversal for Poleknig's custom player.
 *
 * The visible mobile player has exact track labels 01..N and a large square play control.
 * Selecting a label alone does not request the track; the play control must be activated after
 * every selection. Use native MotionEvent taps for both controls because the site's delegated
 * handlers do not reliably react to synthetic DOM click() in Android WebView.
 */
class PoleknigPlayerCapture(private val context: Context) {
    private companion object {
        const val BRIDGE = "AudoibooPoleCapture"
    }

    fun capture(
        manifest: PluginPackageManifest,
        rule: PluginMediaCaptureRule,
        pageUrl: String,
        onComplete: (PluginMediaCaptureResult) -> Unit
    ) {
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
                        if (diagnostics.size < 120) diagnostics += "accepted-$source:${url.take(500)}"
                    }
                }
            }

            fun snapshot(): List<String> {
                val all = synchronized(found) { found.toList() }
                val direct = all.filter { PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, it) }
                val selected = if (rule.preferDirectMedia && direct.isNotEmpty()) direct else all
                return if (rule.sortTrackNumber) {
                    selected.sortedWith(compareBy({ PluginWebViewMediaCaptureRuntime.trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                } else selected
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = snapshot()
                diagnostics += reason
                diagnostics += "pole-native-clicks=$clicks"
                diagnostics += "pole-requests=$requests"
                diagnostics += "media=${media.size}"
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
                    webView.destroy()
                }
                onComplete(PluginMediaCaptureResult(pageUrl, media, diagnostics.toList()))
            }

            fun dispatchTap(x: Float, y: Float) {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(now, now + 55L, MotionEvent.ACTION_UP, x, y, 0)
                webView.dispatchTouchEvent(down)
                webView.dispatchTouchEvent(up)
                down.recycle(); up.recycle()
                clicks++
            }

            fun parseRect(raw: String?): Pair<Float, Float>? = runCatching {
                val text = raw?.takeIf { it != "null" } ?: return@runCatching null
                val o = JSONObject(text)
                o.optDouble("x").toFloat() to o.optDouble("y").toFloat()
            }.getOrNull()

            fun scan() {
                webView.evaluateJavascript(
                    """
                    (()=>{
                      const emit=u=>{try{if(u)AudoibooPoleCapture.media(new URL(String(u),location.href).href)}catch(_){}};
                      document.querySelectorAll('audio,source,a[href],[data-src],[data-url],[data-file],[data-audio]').forEach(e=>{
                        ['src','href','data-src','data-url','data-file','data-audio'].forEach(a=>emit(e.getAttribute&&e.getAttribute(a)));
                        emit(e.currentSrc);
                      });
                      try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){}
                      const h=document.documentElement.outerHTML.replaceAll('\\/','/');
                      const m=h.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+(?:\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\/files\/\d+)(?:\?[^\"'<>\s]*)?/gi)||[];
                      m.slice(0,500).forEach(emit);
                      return m.length;
                    })()
                    """.trimIndent(), null
                )
            }

            fun playTargetScript(): String = """
                (()=>{
                  const vis=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>20&&r.height>20&&s.display!=='none'&&s.visibility!=='hidden'};
                  const all=[...document.querySelectorAll('button,a,[role=button],div,span')].filter(vis);
                  let best=null,bestScore=-1;
                  for(const e of all){
                    const r=e.getBoundingClientRect();
                    if(r.width<55||r.height<55||r.width>230||r.height>230)continue;
                    const ratio=Math.min(r.width,r.height)/Math.max(r.width,r.height);if(ratio<0.62)continue;
                    const cls=String(e.className||''),id=String(e.id||''),aria=String(e.getAttribute?.('aria-label')||''),title=String(e.getAttribute?.('title')||'');
                    const svg=!!e.querySelector?.('svg,path,polygon');
                    const style=getComputedStyle(e);
                    let score=ratio*4 + Math.min(r.width,r.height)/80;
                    if(/play|start|main.?button|control/i.test(cls+' '+id+' '+aria+' '+title))score+=8;
                    if(svg)score+=4;
                    if(r.left<innerWidth*0.45)score+=2;
                    if(r.top<innerHeight*0.75)score+=1;
                    if(style.cursor==='pointer')score+=1;
                    if(score>bestScore){bestScore=score;best=e;}
                  }
                  if(!best){AudoibooPoleCapture.event('play-geom-miss');return null;}
                  const r=best.getBoundingClientRect();
                  AudoibooPoleCapture.event('play-geom:'+best.tagName+':'+String(best.className||'').slice(0,80)+':'+Math.round(r.width)+'x'+Math.round(r.height));
                  best.scrollIntoView({block:'center'});const q=best.getBoundingClientRect();
                  return {x:q.left+q.width/2,y:q.top+q.height/2};
                })()
            """.trimIndent()

            fun trackScript(number: Int): String {
                val label = number.toString().padStart(2, '0')
                return """
                    (()=>{
                      const label=${JSONObject.quote(label)},norm=s=>String(s||'').replace(/\s+/g,' ').trim();
                      let a=[...document.querySelectorAll('body *')].filter(e=>norm(e.innerText||e.textContent)===label);
                      a=a.filter(e=>![...e.children].some(c=>norm(c.innerText||c.textContent)===label));
                      a=a.filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>3&&r.height>3&&r.height<120&&s.display!=='none'&&s.visibility!=='hidden'});
                      a.sort((x,y)=>{const A=x.getBoundingClientRect(),B=y.getBoundingClientRect();return A.width*A.height-B.width*B.height});
                      if(!a.length){AudoibooPoleCapture.event('track-miss:'+label);return null;}
                      const e=a[0];e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();
                      AudoibooPoleCapture.event('track:'+label+':'+e.tagName+':'+String(e.className||'').slice(0,70));
                      return {x:r.left+r.width/2,y:r.top+r.height/2};
                    })()
                """.trimIndent()
            }

            fun traverse(number: Int = 1, misses: Int = 0) {
                if (finished.get()) return
                if (number > 60 || misses >= 3) {
                    diagnostics += "pole-track-stop:number=$number misses=$misses"
                    handler.postDelayed({ finish("pole-complete") }, 900L)
                    return
                }
                webView.evaluateJavascript(trackScript(number)) { rawTrack ->
                    val track = parseRect(rawTrack)
                    if (track == null) {
                        handler.postDelayed({ traverse(number + 1, misses + 1) }, 180L)
                        return@evaluateJavascript
                    }
                    dispatchTap(track.first, track.second)
                    handler.postDelayed({
                        webView.evaluateJavascript(playTargetScript()) { rawPlay ->
                            val play = parseRect(rawPlay)
                            if (play != null) {
                                dispatchTap(play.first, play.second)
                                diagnostics += "pole-play-native=${number.toString().padStart(2, '0')}"
                            } else diagnostics += "pole-play-native-miss=${number.toString().padStart(2, '0')}"
                            handler.postDelayed({
                                scan()
                                traverse(number + 1, 0)
                            }, 430L)
                        }
                    }, 140L)
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
                    if (!value.isNullOrBlank() && diagnostics.size < 140) diagnostics += "js:$value"
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
            handler.postDelayed({ finish("pole-timeout") }, minOf(rule.timeoutMs + 5_000L, 28_000L))
            webView.loadUrl(pageUrl)
        }
    }
}
