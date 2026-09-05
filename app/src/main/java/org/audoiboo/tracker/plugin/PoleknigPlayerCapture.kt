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
                val url = runCatching { val v=raw?.trim()?.replace("\\/", "/").orEmpty(); if(v.isBlank()) return@runCatching null; URI(pageUrl).resolve(v).toString() }.getOrNull() ?: return
                if (!isResolver(url) && !PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, url)) return
                val key = PluginWebViewMediaCaptureRuntime.mediaKey(url) ?: url
                synchronized(found) { if (keys.add(key) && found.size < rule.maxResults) { found += url; if (diagnostics.size < 140) diagnostics += "accepted-$source:${url.take(500)}" } }
            }
            fun snapshot(): List<String> { val all=synchronized(found){found.toList()}; val direct=all.filter{PluginWebViewMediaCaptureRuntime.isMedia(manifest,rule,it)}; val selected=if(rule.preferDirectMedia&&direct.isNotEmpty())direct else all; return if(rule.sortTrackNumber)selected.sortedWith(compareBy({PluginWebViewMediaCaptureRuntime.trackNumber(it)?:Int.MAX_VALUE},{it})) else selected }
            fun finish(reason: String) { if(!finished.compareAndSet(false,true))return; handler.removeCallbacksAndMessages(null); val media=snapshot(); diagnostics+=reason; diagnostics+="pole-native-clicks=$clicks"; diagnostics+="pole-requests=$requests"; diagnostics+="media=${media.size}"; runCatching{webView.stopLoading();webView.loadUrl("about:blank");webView.removeJavascriptInterface(BRIDGE);webView.destroy()}; onComplete(PluginMediaCaptureResult(pageUrl,media,diagnostics.toList())) }
            fun tap(x:Float,y:Float){val now=SystemClock.uptimeMillis();val d=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,x,y,0);val u=MotionEvent.obtain(now,now+55,MotionEvent.ACTION_UP,x,y,0);webView.dispatchTouchEvent(d);webView.dispatchTouchEvent(u);d.recycle();u.recycle();clicks++}
            fun rect(raw:String?):Pair<Float,Float>?=runCatching{val t=raw?.takeIf{it!="null"}?:return@runCatching null;val o=JSONObject(t);o.optDouble("x").toFloat() to o.optDouble("y").toFloat()}.getOrNull()
            fun scan(){webView.evaluateJavascript("""(()=>{const emit=u=>{try{if(u)AudoibooPoleCapture.media(new URL(String(u),location.href).href)}catch(_){}};document.querySelectorAll('audio,source,a[href],[data-src],[data-url],[data-file],[data-audio]').forEach(e=>{['src','href','data-src','data-url','data-file','data-audio'].forEach(a=>emit(e.getAttribute&&e.getAttribute(a)));emit(e.currentSrc)});try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){};const h=document.documentElement.outerHTML.replaceAll('\\/','/');const m=h.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+(?:\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\/files\/\d+)(?:\?[^\"'<>\s]*)?/gi)||[];m.slice(0,500).forEach(emit);return m.length})()""",null)}

            fun primePlayScript(): String = """
                (()=>{
                  const vis=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>8&&r.height>8&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0'&&s.pointerEvents!=='none'};
                  const sels=['button[aria-label*=play i]','[role=button][aria-label*=play i]','button[title*=play i]','.player-play','.play-button','.jp-play','button[class*=play]','[class*=play][role=button]'];
                  for(const s of sels){for(const e of document.querySelectorAll(s)){if(!vis(e))continue;const r=e.getBoundingClientRect();AudoibooPoleCapture.event('prime-selector:'+s+':'+e.tagName+':'+String(e.className||'').slice(0,60));e.scrollIntoView({block:'center'});const q=e.getBoundingClientRect();return{x:q.left+q.width/2,y:q.top+q.height/2}}}
                  let a=[...document.querySelectorAll('button,a,[role=button],[onclick]')].filter(vis).filter(e=>{const r=e.getBoundingClientRect();return r.top<innerHeight*.72&&r.width>=30&&r.height>=30&&r.width<=280&&r.height<=280});
                  a.sort((x,y)=>{const A=x.getBoundingClientRect(),B=y.getBoundingClientRect();return B.width*B.height-A.width*A.height});
                  a.slice(0,5).forEach((e,i)=>{const r=e.getBoundingClientRect();AudoibooPoleCapture.event('prime-cand'+i+':'+e.tagName+':'+String(e.className||'').slice(0,60)+':'+Math.round(r.width)+'x'+Math.round(r.height))});
                  const e=a[0];if(!e){AudoibooPoleCapture.event('prime-miss');return null}e.scrollIntoView({block:'center'});const q=e.getBoundingClientRect();return{x:q.left+q.width/2,y:q.top+q.height/2};
                })()
            """.trimIndent()

            fun trackScript(number:Int):String{val label=number.toString().padStart(2,'0');return """(()=>{const label=${JSONObject.quote(label)},norm=s=>String(s||'').replace(/\s+/g,' ').trim();let a=[...document.querySelectorAll('body *')].filter(e=>norm(e.innerText||e.textContent)===label);a=a.filter(e=>![...e.children].some(c=>norm(c.innerText||c.textContent)===label));a=a.filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>3&&r.height>3&&r.height<120&&s.display!=='none'&&s.visibility!=='hidden'});a.sort((x,y)=>{const A=x.getBoundingClientRect(),B=y.getBoundingClientRect();return A.width*A.height-B.width*B.height});if(!a.length){AudoibooPoleCapture.event('track-miss:'+label);return null}const e=a[0];e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();AudoibooPoleCapture.event('track:'+label+':'+e.tagName+':'+String(e.className||'').slice(0,70));return{x:r.left+r.width/2,y:r.top+r.height/2}})()"""}
            fun traverse(number:Int=1,misses:Int=0){if(finished.get())return;if(number>60||misses>=3){diagnostics+="pole-track-stop:number=$number misses=$misses";handler.postDelayed({finish("pole-complete")},900);return};webView.evaluateJavascript(trackScript(number)){raw->val p=rect(raw);if(p==null){handler.postDelayed({traverse(number+1,misses+1)},180);return@evaluateJavascript};tap(p.first,p.second);handler.postDelayed({scan();traverse(number+1,0)},650)}}

            @SuppressLint("SetJavaScriptEnabled") webView.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false;userAgentString=userAgentString.replace("; wv","")}
            webView.addJavascriptInterface(object{@JavascriptInterface fun media(value:String?)=handler.post{remember(value,"js")};@JavascriptInterface fun event(value:String?)=handler.post{if(!value.isNullOrBlank()&&diagnostics.size<180)diagnostics+= "js:$value"}},BRIDGE)
            webView.webViewClient=object:WebViewClient(){
                override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?):WebResourceResponse?{requests++;remember(request?.url?.toString(),"network");return super.shouldInterceptRequest(view,request)}
                override fun onPageFinished(view:WebView,url:String){if(!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest,rule,url)||finished.get())return;diagnostics+="pole-loaded";handler.postDelayed({scan();webView.evaluateJavascript(primePlayScript()){raw->val p=rect(raw);if(p!=null){tap(p.first,p.second);diagnostics+="pole-prime-play-native"}else diagnostics+="pole-prime-play-miss";handler.postDelayed({traverse()},550)}},900)}
            }
            handler.postDelayed({finish("pole-timeout")},minOf(rule.timeoutMs+8000,32000));webView.loadUrl(pageUrl)
        }
    }
}
