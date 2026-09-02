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
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Device-side media discovery for Knigavuhe pages. */
class KnigavuheWebViewMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)
    fun capture(pageUrl: String, timeoutMs: Long = 20_000L, onComplete: (Result) -> Unit) {
        require(isAllowedPage(pageUrl)) { "Unsupported Knigavuhe URL" }
        Handler(Looper.getMainLooper()).post {
            val found=Collections.synchronizedSet(LinkedHashSet<String>()); val keys=Collections.synchronizedSet(LinkedHashSet<String>())
            val diagnostics=mutableListOf<String>(); val finished=AtomicBoolean(false); val armed=AtomicBoolean(false); val handler=Handler(Looper.getMainLooper()); val webView=WebView(context)
            fun remember(raw:String?){ if(!armed.get())return; val url=raw?.trim()?.replace("&amp;","&").orEmpty(); if(!isBookAudio(url))return; val key=runCatching{val u=URI(url);"${u.scheme?.lowercase()}://${u.host?.lowercase()}${u.path}"}.getOrNull()?:return; synchronized(found){if(found.size<MAX_MEDIA_URLS&&keys.add(key))found+=url} }
            fun snapshot()=synchronized(found){found.toList()}
            fun finish(reason:String){if(!finished.compareAndSet(false,true))return;val media=snapshot();diagnostics+=reason;diagnostics+="media=${media.size}";handler.removeCallbacksAndMessages(null);runCatching{webView.stopLoading();webView.loadUrl("about:blank");webView.removeJavascriptInterface(BRIDGE);(webView.parent as? ViewGroup)?.removeView(webView);webView.destroy()};onComplete(Result(pageUrl,media,diagnostics.toList()))}
            @SuppressLint("SetJavaScriptEnabled") webView.settings.apply{javaScriptEnabled=true;domStorageEnabled=true;mediaPlaybackRequiresUserGesture=false;userAgentString=userAgentString.replace("; wv","")}
            webView.addJavascriptInterface(object{@JavascriptInterface fun media(url:String?)=handler.post{remember(url)};@JavascriptInterface fun event(message:String?)=handler.post{if(!message.isNullOrBlank()&&diagnostics.size<100)diagnostics+="js:$message"}},BRIDGE)
            webView.webViewClient=object:WebViewClient(){override fun shouldInterceptRequest(view:WebView?,request:WebResourceRequest?):WebResourceResponse?{remember(request?.url?.toString());return super.shouldInterceptRequest(view,request)};override fun onPageFinished(view:WebView,url:String){if(!isAllowedPage(url))return;diagnostics+="loaded:${Uri.parse(url).host}";view.evaluateJavascript(INSTALL_HOOKS,null);handler.postDelayed({if(!finished.get())view.evaluateJavascript(PREPARE_PLAYER,null)},700L);handler.postDelayed({if(!finished.get()){synchronized(found){found.clear();keys.clear()};armed.set(true);diagnostics+="capture-armed";view.evaluateJavascript(ACTIVATE_TRACKS,null)}},1800L);listOf(3500L,6000L,9000L,12000L,15000L).forEach{d->handler.postDelayed({if(!finished.get())view.evaluateJavascript(ACTIVATE_TRACKS,null)},d)};handler.postDelayed({if(!finished.get()&&snapshot().isNotEmpty())finish("captured-playlist")},17500L)}}
            handler.postDelayed({finish("timeout")},timeoutMs.coerceAtLeast(20_000L));webView.loadUrl(pageUrl)
        }
    }
    companion object {
        private const val BRIDGE="AudoibooMediaCapture";private const val MAX_MEDIA_URLS=150;private val AUDIO_EXTENSIONS=setOf("mp3","m4a","m4b","aac","ogg","opus","flac","m3u8")
        fun isAllowedPage(url:String)=runCatching{val u=URI(url);val h=u.host?.lowercase().orEmpty();u.scheme?.lowercase() in setOf("http","https")&&(h=="knigavuhe.org"||h.endsWith(".knigavuhe.org"))}.getOrDefault(false)
        fun isBookAudio(url:String)=runCatching{val u=URI(url);val h=u.host?.lowercase().orEmpty();val p=u.path?.lowercase().orEmpty();u.scheme?.lowercase() in setOf("http","https")&&(h=="knigavuhe.org"||h.endsWith(".knigavuhe.org"))&&p.substringAfterLast('.',"") in AUDIO_EXTENSIONS}.getOrDefault(false)
        fun isLikelyTrackLabel(text:String)=text.trim().length in 1..180&&Regex("""(?ix)^\s*(?:\d{1,3}(?:[\s._:)-]+.+)?|.+_\d+)(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?\s*$""").matches(text.trim())
        private val INSTALL_HOOKS="""(()=>{if(window.__audoibooHooks)return'already';window.__audoibooHooks=true;const emit=u=>{try{if(u)AudoibooMediaCapture.media(String(u))}catch(_){}};const f=window.fetch;if(f)window.fetch=function(...a){a.forEach(x=>emit(typeof x==='string'?x:x?.url));return f.apply(this,a)};const o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){emit(u);return o.apply(this,arguments)};const s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s?.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);return s.set.call(this,v)}});return'installed'})();"""
        private val PREPARE_PLAYER="""(()=>{const n=s=>String(s||'').replace(/\s+/g,' ').trim();const all=[...document.querySelectorAll('button,a,div,span,li,label,input')];const full=all.find(e=>/слушать полностью/i.test(n(e.innerText||e.value)));try{full?.click()}catch(_){};const large=all.find(e=>/больш|крупн|длинн.*отрез|фрагмент/i.test(n(e.innerText||e.value)));let c=large?.matches?.('input[type=checkbox]')?large:large?.querySelector?.('input[type=checkbox]');if(!c)c=document.querySelector('input[type=checkbox][name*=large i],input[type=checkbox][id*=large i]');if(c&&!c.checked){try{c.click();c.dispatchEvent(new Event('change',{bubbles:true}));AudoibooMediaCapture.event('enabled-large-segments')}catch(_){}}else AudoibooMediaCapture.event(c?'large-segments-already-enabled':'large-segments-switch-not-found');return true})();"""
        private val ACTIVATE_TRACKS="""(()=>{const emit=u=>{try{if(u)AudoibooMediaCapture.media(String(u))}catch(_){}};const n=s=>String(s||'').replace(/\s+/g,' ').trim();const roots=()=>{const a=[],seen=new Set();const add=r=>{if(!r||seen.has(r))return;seen.add(r);a.push(r);try{r.querySelectorAll('*').forEach(e=>{if(e.shadowRoot)add(e.shadowRoot)});r.querySelectorAll('iframe,frame').forEach(f=>{try{if(f.contentDocument)add(f.contentDocument)}catch(_){}})}catch(_){}};add(document);return a};const tracks=[];roots().forEach(r=>{try{r.querySelectorAll('audio[src],source[src]').forEach(e=>emit(e.currentSrc||e.src));r.querySelectorAll('script').forEach(s=>{const t=String(s.textContent||'').replaceAll('\\/','/');(t.match(/https?:[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\?[^\"'<>\s]*)?/gi)||[]).forEach(emit)});r.querySelectorAll('button,a,div,span,li').forEach(e=>{const t=n(e.innerText||e.textContent);if(t.length<=180&&(/^\d{1,3}(?:\s|$)/.test(t)||/_\d+(?:\s|$)/.test(t)||/^\d{1,2}:\d{2}(?::\d{2})?$/.test(t)))tracks.push(e)})}catch(_){}});const u=[...new Set(tracks)].slice(0,120);u.forEach((e,i)=>setTimeout(()=>{try{e.scrollIntoView({block:'center'});e.click()}catch(_){}},i*150));try{performance.getEntriesByType('resource').forEach(e=>emit(e.name))}catch(_){};AudoibooMediaCapture.event('track-candidates='+u.length);return u.length})();"""
    }
}
