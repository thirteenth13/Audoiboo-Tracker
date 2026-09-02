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
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Device-side media discovery for Lis10book's dynamically loaded player. */
class Lis10BookWebViewMediaCapture(private val context: Context) {
 data class Result(val pageUrl:String,val mediaUrls:List<String>,val diagnostics:List<String>)
 fun capture(pageUrl:String,timeoutMs:Long=24_000L,onComplete:(Result)->Unit){require(isAllowedPage(pageUrl));Handler(Looper.getMainLooper()).post{
  val found=Collections.synchronizedSet(LinkedHashSet<String>());val keys=Collections.synchronizedSet(LinkedHashSet<String>());val diagnostics=mutableListOf<String>();val finished=AtomicBoolean(false);val armed=AtomicBoolean(false);val h=Handler(Looper.getMainLooper());val w=WebView(context)
  fun remember(raw:String?){if(!armed.get())return;val u=raw?.trim()?.replace("&amp;","&").orEmpty();if(!isAudioUrl(u))return;val k=mediaKey(u)?:return;synchronized(found){if(found.size<MAX_MEDIA_URLS&&keys.add(k))found+=u}}
  fun snap()=synchronized(found){found.toList().sortedWith(compareBy({trackNumber(it)?:Int.MAX_VALUE},{it}))}
  fun finish(r:String){if(!finished.compareAndSet(false,true))return;val m=snap();diagnostics+=r;diagnostics+="media=${m.size}";h.removeCallbacksAndMessages(null);runCatching{w.stopLoading();w.loadUrl("about:blank");w.removeJavascriptInterface(BRIDGE);(w.parent as? ViewGroup)?.removeView(w);w.destroy()};onComplete(Result(pageUrl,m,diagnostics.toList()))}
  @SuppressLint("SetJavaScriptEnabled") w.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false;userAgentString=userAgentString.replace("; wv","")}
  w.addJavascriptInterface(object{@JavascriptInterface fun media(u:String?)=h.post{remember(u)};@JavascriptInterface fun event(s:String?)=h.post{if(!s.isNullOrBlank()&&diagnostics.size<100)diagnostics+="js:$s"}},BRIDGE)
  w.webViewClient=object:WebViewClient(){override fun shouldInterceptRequest(v:WebView?,r:WebResourceRequest?):WebResourceResponse?{remember(r?.url?.toString());return super.shouldInterceptRequest(v,r)};override fun onPageFinished(v:WebView,u:String){if(!isAllowedPage(u))return;diagnostics+="loaded";v.evaluateJavascript(INSTALL_HOOKS,null);h.postDelayed({if(!finished.get()){synchronized(found){found.clear();keys.clear()};armed.set(true);diagnostics+="capture-armed";v.evaluateJavascript(ACTIVATE_AND_SCAN,null)}},700L);listOf(2500L,5000L,8000L,11000L,14000L,17000L,20000L).forEach{d->h.postDelayed({if(!finished.get())v.evaluateJavascript(ACTIVATE_AND_SCAN,null)},d)};h.postDelayed({if(!finished.get()&&snap().isNotEmpty())finish("playlist-scan-complete")},22000L)}}
  h.postDelayed({finish("timeout")},timeoutMs.coerceAtLeast(24_000L));w.loadUrl(pageUrl)
 }}
 companion object{
  private const val BRIDGE="AudoibooLis10Capture";private const val MAX_MEDIA_URLS=300;private val AUDIO_EXTENSIONS=setOf("mp3","m4a","m4b","aac","ogg","opus","flac","m3u8")
  fun isAllowedPage(url:String)=runCatching{val u=URI(url.trim());val h=u.host?.lowercase().orEmpty();u.scheme?.lowercase() in setOf("http","https")&&(h=="lis10book.com"||h.endsWith(".lis10book.com"))&&u.path.orEmpty().startsWith("/audio/")}.getOrDefault(false)
  fun isAudioUrl(url:String)=runCatching{val u=URI(url.trim());u.scheme?.lowercase() in setOf("http","https")&&u.path.orEmpty().lowercase().substringAfterLast('.',"") in AUDIO_EXTENSIONS}.getOrDefault(false)
  fun mediaKey(url:String):String?=runCatching{val u=URI(url.trim());"${u.scheme?.lowercase()}://${u.host?.lowercase()}${u.path}"}.getOrNull()
  fun trackNumber(url:String):Int?=runCatching{Regex("(?<!\\d)(\\d{1,4})(?!\\d)").findAll(URI(url).path.substringAfterLast('/').substringBeforeLast('.')).mapNotNull{it.groupValues[1].toIntOrNull()}.lastOrNull()}.getOrNull()
  private val INSTALL_HOOKS="""(()=>{if(window.__audoibooLis10Hooks)return'already';window.__audoibooLis10Hooks=true;const emit=u=>{try{if(u)AudoibooLis10Capture.media(String(u))}catch(_){}};const f=window.fetch;if(f)window.fetch=function(...a){a.forEach(x=>emit(typeof x==='string'?x:x?.url));return f.apply(this,a)};const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){emit(u);return o.apply(this,arguments)};const s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s?.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);return s.set.call(this,v)}});const A=window.Audio;window.Audio=function(src){const a=new A(src);emit(src);return a};window.Audio.prototype=A.prototype;return'installed'})();"""
  private val ACTIVATE_AND_SCAN="""(()=>{const emit=u=>{try{if(u)AudoibooLis10Capture.media(String(u))}catch(_){}};const n=s=>String(s||'').replace(/\s+/g,' ').trim();const roots=()=>{const a=[],seen=new Set();const add=r=>{if(!r||seen.has(r))return;seen.add(r);a.push(r);try{r.querySelectorAll('*').forEach(e=>{if(e.shadowRoot)add(e.shadowRoot)});r.querySelectorAll('iframe,frame').forEach(f=>{try{if(f.contentDocument)add(f.contentDocument)}catch(_){}})}catch(_){}};add(document);return a};const tracks=[];roots().forEach(r=>{try{r.querySelectorAll('audio[src],source[src]').forEach(e=>emit(e.currentSrc||e.src));r.querySelectorAll('script').forEach(s=>{const t=String(s.textContent||s.innerHTML||'').replaceAll('\\/','/');(t.match(/https?:[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\?[^\"'<>\s]*)?/gi)||[]).forEach(emit)});r.querySelectorAll('button,a,div,span,li').forEach(e=>{const t=n(e.innerText||e.textContent);if(t.length<=160&&(/^0?\d{1,3}$/.test(t)||/слушать|воспроизвести|play|▶/i.test(t)))tracks.push(e)})}catch(_){}});const u=[...new Set(tracks)].slice(0,160);u.forEach((e,i)=>setTimeout(()=>{try{e.scrollIntoView({block:'center'});e.click()}catch(_){}},i*150));try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){};AudoibooLis10Capture.event('player-candidates='+u.length);return u.length})();"""
 }
}
