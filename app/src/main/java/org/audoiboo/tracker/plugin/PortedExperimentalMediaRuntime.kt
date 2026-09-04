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
import java.util.concurrent.atomic.AtomicInteger

/** Android port of the proven Scrapling/CDP media traversal experiments. */
object PortedExperimentalMediaRuntime {
    private const val BRIDGE = "AudoibooPortedCapture"
    private val supported = setOf("knigavuhe", "poleknig", "izib", "lis10book")

    fun supports(pluginId: String): Boolean = pluginId in supported

    fun capture(
        context: Context,
        manifest: PluginPackageManifest,
        rule: PluginMediaCaptureRule,
        pageUrl: String,
        onComplete: (PluginMediaCaptureResult) -> Unit
    ) {
        Handler(Looper.getMainLooper()).post {
            val found = LinkedHashSet<String>()
            val keys = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val started = AtomicBoolean(false)
            val captureArmed = AtomicBoolean(manifest.id != "knigavuhe")
            val requests = AtomicInteger(0)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            var clicks = 0

            fun isResolver(url: String): Boolean = runCatching {
                val regex = rule.resolverPathRegex ?: return@runCatching false
                val uri = URI(url)
                val host = uri.host?.lowercase().orEmpty()
                manifest.hosts.any { host == it || host.endsWith(".$it") } && regex.matches(uri.path.orEmpty())
            }.getOrDefault(false)

            fun isAcceptedMedia(url: String): Boolean =
                PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, url) || trustedExternalMedia(manifest.id, url)

            fun remember(raw: String?, source: String) {
                if (!captureArmed.get()) return
                val url = normalize(raw, pageUrl) ?: return
                val accepted = isAcceptedMedia(url) || isResolver(url)
                synchronized(found) {
                    if (!accepted) {
                        if (looksAudio(url) && diagnostics.size < 120) diagnostics += "rejected-$source:${host(url)}:${url.take(500)}"
                        return
                    }
                    val key = PluginWebViewMediaCaptureRuntime.mediaKey(url) ?: url
                    if (keys.add(key) && found.size < rule.maxResults) {
                        found += url
                        if (diagnostics.size < 140) diagnostics += "accepted-$source:${url.take(700)}"
                    }
                }
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val all = synchronized(found) { found.toList() }
                val direct = all.filter(::isAcceptedMedia)
                val selected = if (rule.preferDirectMedia && direct.isNotEmpty()) direct else all
                val sorted = if (rule.sortTrackNumber) selected.sortedWith(compareBy({ PluginWebViewMediaCaptureRuntime.trackNumber(it) ?: Int.MAX_VALUE }, { it })) else selected
                synchronized(found) {
                    diagnostics += reason
                    diagnostics += "ported-clicks=$clicks"
                    diagnostics += "ported-requests=${requests.get()}"
                    diagnostics += "media=${sorted.size}"
                }
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
                    webView.destroy()
                }
                onComplete(PluginMediaCaptureResult(pageUrl, sorted, synchronized(found) { diagnostics.toList() }))
            }

            fun startCapture(view: WebView, source: String) {
                if (finished.get() || !started.compareAndSet(false, true)) return
                diagnostics += "ported-start:$source"
                view.evaluateJavascript(installHooks(manifest.id), null)
                handler.postDelayed({
                    if (finished.get()) return@postDelayed
                    if (manifest.id == "knigavuhe") {
                        synchronized(found) { found.clear(); keys.clear() }
                        captureArmed.set(true)
                        diagnostics += "ported-armed"
                    }
                    prepareAndTraverse(view, manifest.id, handler, diagnostics) { clicks++ }
                }, if (manifest.id == "knigavuhe") 2_200L else 700L)
                handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(scanScript(manifest.id), null) }, 3_200L)
                handler.postDelayed({ if (!finished.get()) finish("ported-finished") }, minOf(rule.timeoutMs, 24_000L))
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
                @JavascriptInterface fun body(value: String?) = handler.post { scanCandidates(value, pageUrl).forEach { remember(it, "body") } }
                @JavascriptInterface fun event(value: String?) = handler.post {
                    if (!value.isNullOrBlank() && diagnostics.size < 140) synchronized(found) { diagnostics += "js:$value" }
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    requests.incrementAndGet()
                    remember(request?.url?.toString(), "network")
                    return super.shouldInterceptRequest(view, request)
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    if (PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url)) startCapture(view, "commit-visible")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url) || finished.get()) return
                    diagnostics += "ported-loaded"
                    startCapture(view, "page-finished")
                }
            }

            handler.postDelayed({ if (!finished.get() && !started.get()) startCapture(webView, "fallback-timer") }, 2_000L)
            handler.postDelayed({ finish("ported-timeout") }, minOf(rule.timeoutMs + 1_500L, 26_000L))
            webView.loadUrl(pageUrl)
        }
    }

    private fun prepareAndTraverse(view: WebView, site: String, handler: Handler, diagnostics: MutableList<String>, clicked: () -> Unit) {
        if (site == "knigavuhe") {
            view.evaluateJavascript(markActionScript("(?:^|\\s)Слушать полностью(?:\\s|$)")) { raw ->
                val rect = parseRect(raw)
                if (rect != null) {
                    diagnostics += "ported-prepare-hit"
                    dispatchTap(view, rect.first, rect.second)
                    clicked()
                } else diagnostics += "ported-prepare-miss"
                handler.postDelayed({ probeKnigavuheApi(view, diagnostics) }, 450L)
                handler.postDelayed({ traverseSequential(view, site, handler, diagnostics, clicked, firstTrackWaits = 0) }, 1_500L)
            }
        } else traverseSequential(view, site, handler, diagnostics, clicked)
        handler.postDelayed({ view.evaluateJavascript(scanScript(site), null) }, 5_500L)
    }

    private fun probeKnigavuheApi(view: WebView, diagnostics: MutableList<String>) {
        val findIdScript = """
            (function(){
              var h=document.documentElement.outerHTML.replace(/\\\//g,'/');
              var m=h.match(/\/play\/id\/(\d+)/i)||h.match(/\/covers\/(\d+)\//i)||h.match(/book[_-]?id[^0-9]{0,20}(\d{4,})/i);
              return m?m[1]:'';
            })()
        """.trimIndent()
        view.evaluateJavascript(findIdScript) { raw ->
            val id = raw.trim('"')
            if (id.isBlank()) { diagnostics += "kv-play-api:book-id-miss"; return@evaluateJavascript }
            diagnostics += "kv-play-api:id=$id"
            val probeScript = """
                fetch('/play/id/$id',{headers:{'X-Requested-With':'XMLHttpRequest','Accept':'application/json,text/plain,*/*'}})
                  .then(r=>r.text())
                  .then(t=>{AudoibooPortedCapture.body(t);AudoibooPortedCapture.event('kv-play-body='+t.length)})
                  .catch(()=>AudoibooPortedCapture.event('kv-play-error'))
            """.trimIndent()
            view.evaluateJavascript(probeScript, null)
        }
    }

    private fun traverseSequential(
        view: WebView,
        site: String,
        handler: Handler,
        diagnostics: MutableList<String>,
        clicked: () -> Unit,
        index: Int = 0,
        misses: Int = 0,
        firstTrackWaits: Int = 0
    ) {
        if (index >= 60 || misses >= 4) {
            diagnostics += "ported-track-stop:index=$index misses=$misses"
            return
        }
        view.evaluateJavascript(trackTargetScript(site, index)) { raw ->
            val rect = parseRect(raw)
            if (rect == null) {
                if (site == "knigavuhe" && index == 0 && firstTrackWaits < 14) {
                    diagnostics += "kv-playlist-wait=${firstTrackWaits + 1}"
                    handler.postDelayed({ traverseSequential(view, site, handler, diagnostics, clicked, 0, 0, firstTrackWaits + 1) }, 500L)
                } else {
                    handler.postDelayed({ traverseSequential(view, site, handler, diagnostics, clicked, index + 1, misses + 1, firstTrackWaits) }, 150L)
                }
            } else {
                if (site != "poleknig") dispatchTap(view, rect.first, rect.second)
                clicked()
                handler.postDelayed({
                    view.evaluateJavascript(scanScript(site), null)
                    traverseSequential(view, site, handler, diagnostics, clicked, index + 1, 0, firstTrackWaits)
                }, if (site == "poleknig") 520L else 360L)
            }
        }
    }

    private fun dispatchTap(view: WebView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 45L, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down); view.dispatchTouchEvent(up); down.recycle(); up.recycle()
    }

    private fun markActionScript(pattern: String): String = """
        (function(){
          const n=s=>String(s||'').replace(/\s+/g,' ').trim(),r=new RegExp(${JSONObject.quote(pattern)},'i');
          let a=[...document.querySelectorAll('body *')].filter(e=>r.test(n(e.innerText||e.textContent)));
          let l=a.filter(e=>![...e.children].some(c=>r.test(n(c.innerText||c.textContent))));if(l.length)a=l;
          a=a.filter(e=>{let q=e.getBoundingClientRect(),s=getComputedStyle(e);return q.width>4&&q.height>4&&q.height<180&&s.display!='none'&&s.visibility!='hidden'});
          if(!a.length)return null;
          let e=a[0].closest('button,a,[role=button],[onclick]')||a[0];e.scrollIntoView({block:'center'});
          let q=e.getBoundingClientRect();return {x:q.left+q.width*.2,y:q.top+q.height/2};
        })()
    """.trimIndent()

    private fun trackTargetScript(site: String, index: Int): String = """
        (function(){
          const site=${JSONObject.quote(site)},idx=$index,n=s=>String(s||'').replace(/\s+/g,' ').trim();
          const oneNumber=idx+1,one=String(oneNumber),onePadded=one.padStart(2,'0');
          const pureNumber=t=>/^\d{1,3}$/.test(t)?Number(t):null;
          const matches=t=>{
            t=n(t);if(!t)return false;
            if(site==='poleknig')return t===onePadded;
            if(site==='izib'){
              const m=t.match(/(?:^|\s)(\d{1,3})(?:\s+\d{1,2}:\d{2})?$/);
              return !!m&&Number(m[1])===oneNumber;
            }
            if(site==='knigavuhe'){
              // Real mobile rows are "Book title_0 ... Book title_38" and duration can be H:MM:SS.
              // Match the _N suffix, not the separate 00/01 duration/position labels.
              const m=t.match(/_(\d+)(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?$/);
              return !!m&&Number(m[1])===idx;
            }
            if(site==='lis10book'){
              if(pureNumber(t)===oneNumber)return true;
              const m=t.match(/^(?:трек|глава|часть)\s*0*(\d{1,3})(?:\s|$)/i);
              return !!m&&Number(m[1])===oneNumber;
            }
            return false;
          };
          let a=[...document.querySelectorAll('body *')].filter(e=>matches(e.innerText||e.textContent));
          let l=a.filter(e=>![...e.children].some(c=>matches(c.innerText||c.textContent)));if(l.length)a=l;
          a=a.filter(e=>{let q=e.getBoundingClientRect(),s=getComputedStyle(e);return q.width>3&&q.height>3&&q.height<180&&s.display!=='none'&&s.visibility!=='hidden'});
          a.sort((x,y)=>{let a=x.getBoundingClientRect(),b=y.getBoundingClientRect();return a.width*a.height-b.width*b.height});
          AudoibooPortedCapture.event('target-'+idx+'='+a.length);
          let e=a[0];if(!e)return null;
          let c=site==='poleknig'?(e.closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]')||e):(e.closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file],li,tr,[class*=track],[class*=playlist] > *,[class*=audio] > *')||e);
          let cq=c.getBoundingClientRect(),cs=getComputedStyle(c);if(cq.height>220||cq.height<3||cs.display==='none'||cs.visibility==='hidden')c=e;
          c.scrollIntoView({block:'center'});let q=c.getBoundingClientRect();
          AudoibooPortedCapture.event('tap='+idx+':'+n(e.innerText||e.textContent).slice(0,100));
          if(site==='poleknig'){
            try{AudoibooPortedCapture.body((c.outerHTML||'')+'\n'+(c.parentElement?.outerHTML||''))}catch(_){}
            try{c.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,view:window}))}catch(_){}
            try{c.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,view:window}))}catch(_){}
            try{c.click()}catch(_){}
            // The numbered row selects a track. The large play control is what asks /files/<id>
            // for the selected track, so trigger it after every selection while media is muted.
            setTimeout(()=>{
              try{
                const selectors=[
                  "button[aria-label*='play' i]","[role='button'][aria-label*='play' i]","button[title*='play' i]",
                  ".player-play",".play-button",".jp-play","button[class*='play']","[class*='play'][role='button']"
                ];
                let p=null;
                for(const s of selectors){const x=document.querySelector(s);if(x){const r=x.getBoundingClientRect(),st=getComputedStyle(x);if(r.width>8&&r.height>8&&st.display!=='none'&&st.visibility!=='hidden'){p=x;break}}}
                if(!p){
                  const all=[...document.querySelectorAll('button,[role=button],div,span')];
                  p=all.find(x=>{const r=x.getBoundingClientRect(),t=n(x.innerText||x.textContent),cl=String(x.className||'');return r.width>40&&r.height>40&&r.width<220&&r.height<220&&(/play/i.test(cl)||t==='▶'||t==='►')})||null;
                }
                if(p){
                  p.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,view:window}));
                  p.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,view:window}));
                  p.click();
                  AudoibooPortedCapture.event('pole-play='+onePadded);
                }else AudoibooPortedCapture.event('pole-play-miss='+onePadded);
              }catch(_){AudoibooPortedCapture.event('pole-play-error='+onePadded)}
            },90);
            setTimeout(()=>{
              try{
                document.querySelectorAll('audio,source').forEach(x=>{window.__audoibooPortedEmit?.(x.currentSrc);window.__audoibooPortedEmit?.(x.src);window.__audoibooPortedEmit?.(x.getAttribute?.('src'))});
                AudoibooPortedCapture.body(document.documentElement.outerHTML);
              }catch(_){}
            },360);
            AudoibooPortedCapture.event('dom-click='+onePadded);
          }
          return {x:q.left+Math.min(Math.max(14,q.width*.12),q.width/2),y:q.top+q.height/2};
        })()
    """.trimIndent()

    private fun installHooks(site: String): String = """
        (function(){if(window.__audoibooPorted)return;window.__audoibooPorted=true;
          const site=${JSONObject.quote(site)},broad=site!=='knigavuhe';
          const emit=u=>{try{if(u)AudoibooPortedCapture.media(new URL(String(u),location.href).href)}catch(e){}};
          const scan=t=>{try{let v=String(t||'').replaceAll('\\/','/');let a=v.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+(?:\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\/files\/\d+)(?:\?[^\"'<>\s]*)?/gi)||[];a.forEach(emit)}catch(e){}};
          window.__audoibooPortedEmit=emit;window.__audoibooPortedScan=scan;
          try{
            const mp=HTMLMediaElement.prototype;
            const muted=Object.getOwnPropertyDescriptor(mp,'muted');
            if(muted&&muted.set)Object.defineProperty(mp,'muted',{configurable:true,enumerable:muted.enumerable,get:muted.get,set(v){return muted.set.call(this,true)}});
            const volume=Object.getOwnPropertyDescriptor(mp,'volume');
            if(volume&&volume.set)Object.defineProperty(mp,'volume',{configurable:true,enumerable:volume.enumerable,get:volume.get,set(v){return volume.set.call(this,0)}});
            const p=mp.play;mp.play=function(){try{if(muted&&muted.set)muted.set.call(this,true);if(volume&&volume.set)volume.set.call(this,0)}catch(_){};return p.apply(this,arguments)};
            const silence=()=>document.querySelectorAll('audio,video').forEach(x=>{try{if(muted&&muted.set)muted.set.call(x,true);if(volume&&volume.set)volume.set.call(x,0);x.setAttribute('muted','')}catch(_){}});
            silence();setInterval(silence,100);
          }catch(_){}
          try{
            const A=window.Audio;
            if(A){window.Audio=function(src){const a=new A(src);try{a.muted=true;a.volume=0}catch(_){};emit(src);return a};window.Audio.prototype=A.prototype;}
          }catch(_){}
          if(broad){
            try{let f=window.fetch;if(f)window.fetch=function(...a){a.forEach(scan);return f.apply(this,a).then(r=>{try{scan(r.url);r.clone().text().then(t=>{scan(t);AudoibooPortedCapture.body(t)}).catch(()=>{})}catch(e){}return r})}}catch(e){}
            try{let o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(...a){a.forEach(scan);this.addEventListener('load',()=>{try{scan(this.responseURL);scan(this.responseText);AudoibooPortedCapture.body(this.responseText)}catch(e){}});return o.apply(this,a)}}catch(e){}
          }
          try{let s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s&&s.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);try{this.muted=true;this.volume=0}catch(_){}return s.set.call(this,v)}})}catch(e){}
          return true;
        })()
    """.trimIndent()

    private fun scanScript(site: String): String = """
        (function(){
          const site=${JSONObject.quote(site)},e=window.__audoibooPortedEmit||(()=>{}),s=window.__audoibooPortedScan||(()=>{});
          document.querySelectorAll('audio,source').forEach(x=>{try{if('muted' in x)x.muted=true;if('volume' in x)x.volume=0}catch(_){};e(x.currentSrc);e(x.src);e(x.getAttribute&&x.getAttribute('src'))});
          if(site==='knigavuhe') return true;
          document.querySelectorAll('a[href],[data-src],[data-url],[data-file],[data-audio]').forEach(x=>['href','data-src','data-url','data-file','data-audio'].forEach(a=>e(x.getAttribute&&x.getAttribute(a))));
          try{performance.getEntriesByType('resource').forEach(x=>e(x.name))}catch(_){}
          s(document.documentElement.outerHTML);return true
        })()
    """.trimIndent()

    private fun parseRect(raw: String?): Pair<Float, Float>? = runCatching {
        val text = raw?.takeIf { it != "null" } ?: return@runCatching null
        val o = JSONObject(text)
        o.optDouble("x").toFloat() to o.optDouble("y").toFloat()
    }.getOrNull()

    private fun normalize(raw: String?, base: String): String? = runCatching {
        val value = raw?.trim()?.replace("\\/", "/").orEmpty()
        if (value.isBlank()) return@runCatching null
        URI(base).resolve(value).toString()
    }.getOrNull()

    private fun host(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun looksAudio(url: String): Boolean = Regex("""\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:[?#]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun trustedExternalMedia(site: String, url: String): Boolean {
        if (!looksAudio(url)) return false
        val lower = url.lowercase()
        return when (site) {
            "izib" -> lower.startsWith("https://abookfiles.online/") || Regex("""^https://[^/]+\.abookfiles\.online/""").containsMatchIn(lower)
            "lis10book" -> lower.startsWith("https://fantbox.net/") || Regex("""^https://[^/]+\.fantbox\.net/""").containsMatchIn(lower)
            else -> false
        }
    }

    private fun scanCandidates(text: String?, base: String): List<String> {
        val clean = text.orEmpty().replace("\\/", "/")
        val audio = Regex("""https?://[^\\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\?[^\\"'<>\s]*)?""", RegexOption.IGNORE_CASE).findAll(clean).map { it.value }
        val resolver = Regex("""(?:https?://[^\\"'<>\s]+)?/files/\d+(?:\?[^\\"'<>\s]*)?""", RegexOption.IGNORE_CASE).findAll(clean).mapNotNull { normalize(it.value, base) }
        return (audio + resolver).distinct().take(700).toList()
    }
}
