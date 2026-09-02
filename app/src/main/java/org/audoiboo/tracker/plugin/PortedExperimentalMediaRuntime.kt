package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Android port of the proven Scrapling/CDP track traversal experiments. */
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

            fun remember(raw: String?, source: String) {
                val url = normalize(raw, pageUrl) ?: return
                val accepted = PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, url) || isResolver(url)
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
                val direct = all.filter { PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, it) }
                val selected = if (rule.preferDirectMedia && direct.isNotEmpty()) direct else all
                val sorted = if (rule.sortTrackNumber) selected.sortedWith(compareBy({ PluginWebViewMediaCaptureRuntime.trackNumber(it) ?: Int.MAX_VALUE }, { it })) else selected
                synchronized(found) {
                    diagnostics += reason
                    diagnostics += "ported-clicks=$clicks"
                    diagnostics += "ported-requests=${requests.get()}"
                    diagnostics += "media=${sorted.size}"
                }
                runCatching { webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeJavascriptInterface(BRIDGE); webView.destroy() }
                onComplete(PluginMediaCaptureResult(pageUrl, sorted, synchronized(found) { diagnostics.toList() }))
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
                @JavascriptInterface fun body(value: String?) = handler.post { scanText(value).forEach { remember(it, "body") } }
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

                override fun onPageFinished(view: WebView, url: String) {
                    if (!PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, url) || finished.get()) return
                    synchronized(found) { diagnostics += "ported-loaded" }
                    view.evaluateJavascript(installHooks(), null)
                    handler.postDelayed({
                        if (!finished.get()) prepareAndTraverse(view, manifest.id, handler, diagnostics, { clicks++ })
                    }, 700L)
                    handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(scanScript(), null) }, 2500L)
                    handler.postDelayed({ if (!finished.get()) finish("ported-finished") }, minOf(rule.timeoutMs, 16_000L))
                }
            }
            handler.postDelayed({ finish("ported-timeout") }, minOf(rule.timeoutMs + 1000L, 18_000L))
            webView.loadUrl(pageUrl)
        }
    }

    private fun prepareAndTraverse(
        view: WebView,
        site: String,
        handler: Handler,
        diagnostics: MutableList<String>,
        clicked: () -> Unit
    ) {
        if (site == "knigavuhe") {
            view.evaluateJavascript(markActionScript("(?:^|\\s)Слушать полностью(?:\\s|$)")) { json ->
                clickMarked(view, json, handler, diagnostics, clicked) {
                    handler.postDelayed({ probeKnigavuheApi(view, diagnostics) }, 450L)
                    handler.postDelayed({ traverseTracks(view, site, handler, diagnostics, clicked) }, 900L)
                }
            }
        } else {
            traverseTracks(view, site, handler, diagnostics, clicked)
        }
        handler.postDelayed({ view.evaluateJavascript(scanScript(), null) }, 5500L)
    }

    private fun probeKnigavuheApi(view: WebView, diagnostics: MutableList<String>) {
        view.evaluateJavascript("(function(){var h=document.documentElement.outerHTML.replace(/\\\\\//g,'/');var m=h.match(/\\/play\\/id\\/(\\d+)/i)||h.match(/\\/covers\\/(\\d+)\\//i)||h.match(/book[_-]?id[^0-9]{0,20}(\\d{4,})/i);return m?m[1]:''})()") { raw ->
            val id = raw.trim('"')
            if (id.isBlank()) { diagnostics += "kv-play-api:book-id-miss"; return@evaluateJavascript }
            diagnostics += "kv-play-api:id=$id"
            view.evaluateJavascript("fetch('/play/id/$id',{headers:{'X-Requested-With':'XMLHttpRequest','Accept':'application/json,text/plain,*/*'}}).then(r=>r.text()).then(t=>{AudoibooPortedCapture.body(t);AudoibooPortedCapture.event('kv-play-body='+t.length)}).catch(()=>AudoibooPortedCapture.event('kv-play-error'))", null)
        }
    }

    private fun traverseTracks(view: WebView, site: String, handler: Handler, diagnostics: MutableList<String>, clicked: () -> Unit) {
        view.evaluateJavascript(trackRectsScript(site)) { raw ->
            val rects = parseRects(raw)
            diagnostics += "ported-track-targets=${rects.size}"
            rects.take(50).forEachIndexed { index, rect ->
                handler.postDelayed({ dispatchTap(view, rect.first, rect.second); clicked() }, index * 190L)
            }
        }
    }

    private fun clickMarked(view: WebView, raw: String?, handler: Handler, diagnostics: MutableList<String>, clicked: () -> Unit, after: () -> Unit) {
        val rect = parseRect(raw)
        if (rect == null) { diagnostics += "ported-prepare-miss"; after(); return }
        diagnostics += "ported-prepare-hit"
        dispatchTap(view, rect.first, rect.second)
        clicked()
        handler.postDelayed(after, 350L)
    }

    private fun dispatchTap(view: WebView, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 45L, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down); view.dispatchTouchEvent(up)
        down.recycle(); up.recycle()
    }

    private fun markActionScript(pattern: String): String = """
        (function(){const n=s=>String(s||'').replace(/\\s+/g,' ').trim(),r=new RegExp(${JSONObject.quote(pattern)},'i');let a=[...document.querySelectorAll('body *')].filter(e=>r.test(n(e.innerText||e.textContent)));let l=a.filter(e=>![...e.children].some(c=>r.test(n(c.innerText||c.textContent))));if(l.length)a=l;a=a.filter(e=>{let q=e.getBoundingClientRect(),s=getComputedStyle(e);return q.width>4&&q.height>4&&q.height<180&&s.display!='none'&&s.visibility!='hidden'});if(!a.length)return null;let e=a[0].closest('button,a,[role=button],[onclick]')||a[0];e.scrollIntoView({block:'center'});let q=e.getBoundingClientRect();return {x:q.left+q.width*.2,y:q.top+q.height/2}})()
    """.trimIndent()

    private fun trackRectsScript(site: String): String = """
        (function(){const site=${JSONObject.quote(site)},n=s=>String(s||'').replace(/\\s+/g,' ').trim();const match=t=>{t=n(t);if(site==='poleknig')return /^\\d{2}$/.test(t);if(site==='izib')return /(?:^|\\s)\\d{2}(?:\\s+\\d{1,2}:\\d{2})?$/.test(t);if(site==='knigavuhe')return /_\\d+(?:\\s+\\d{1,2}:\\d{2})?$/.test(t)||/^\\d{2}(?:\\s+\\d{1,2}:\\d{2})?$/.test(t);if(site==='lis10book')return /^(?:\\d{1,3}|(?:трек|глава|часть)\\s*\\d+)/i.test(t);return false};let a=[...document.querySelectorAll('body *')].filter(e=>match(e.innerText||e.textContent));let l=a.filter(e=>![...e.children].some(c=>match(c.innerText||c.textContent)));if(l.length)a=l;let out=[],seen=new Set;for(let e of a){let q=e.getBoundingClientRect(),s=getComputedStyle(e);if(q.width<3||q.height<3||q.height>160||s.display==='none'||s.visibility==='hidden')continue;let t=n(e.innerText||e.textContent);if(seen.has(t))continue;seen.add(t);let c=e.closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]')||e;c.scrollIntoView({block:'center'});q=c.getBoundingClientRect();out.push({x:q.left+Math.min(Math.max(14,q.width*.12),q.width/2),y:q.top+q.height/2,t:t.slice(0,100)});if(out.length>=60)break}AudoibooPortedCapture.event('targets='+out.length);return out})()
    """.trimIndent()

    private fun installHooks(): String = """
        (function(){if(window.__audoibooPorted)return;window.__audoibooPorted=true;const emit=u=>{try{if(u)AudoibooPortedCapture.media(new URL(String(u),location.href).href)}catch(e){}};const scan=t=>{try{let v=String(t||'').replaceAll('\\/','/');let a=v.match(/(?:https?:\\/\\/|\\/\\/|\\/)[^\"'<>\\s]+(?:\\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)|\\/files\\/\\d+)(?:\\?[^\"'<>\\s]*)?/gi)||[];a.forEach(emit)}catch(e){}};window.__audoibooPortedEmit=emit;window.__audoibooPortedScan=scan;try{let f=window.fetch;if(f)window.fetch=function(...a){a.forEach(scan);return f.apply(this,a).then(r=>{try{r.clone().text().then(t=>{scan(t);AudoibooPortedCapture.body(t)}).catch(()=>{})}catch(e){}return r})}}catch(e){};try{let o=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(...a){a.forEach(scan);this.addEventListener('load',()=>{try{scan(this.responseText);AudoibooPortedCapture.body(this.responseText)}catch(e){}});return o.apply(this,a)}}catch(e){};try{let s=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(s&&s.set)Object.defineProperty(HTMLMediaElement.prototype,'src',{configurable:s.configurable,enumerable:s.enumerable,get:s.get,set(v){emit(v);return s.set.call(this,v)}})}catch(e){};return true})()
    """.trimIndent()

    private fun scanScript(): String = """
        (function(){const e=window.__audoibooPortedEmit||(()=>{}),s=window.__audoibooPortedScan||(()=>{});document.querySelectorAll('audio[src],audio source[src],source[src],a[href],[data-src],[data-url],[data-file],[data-audio]').forEach(x=>['src','href','data-src','data-url','data-file','data-audio'].forEach(a=>e(x.getAttribute&&x.getAttribute(a))));try{performance.getEntriesByType('resource').forEach(x=>e(x.name))}catch(_){};s(document.documentElement.outerHTML);return true})()
    """.trimIndent()

    private fun parseRects(raw: String?): List<Pair<Float, Float>> = runCatching {
        val text = raw?.takeIf { it != "null" } ?: return@runCatching emptyList()
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let { o -> o.optDouble("x").toFloat() to o.optDouble("y").toFloat() } }
    }.getOrDefault(emptyList())

    private fun parseRect(raw: String?): Pair<Float, Float>? = runCatching {
        val o = JSONObject(raw ?: return@runCatching null)
        o.optDouble("x").toFloat() to o.optDouble("y").toFloat()
    }.getOrNull()

    private fun normalize(raw: String?, base: String): String? = runCatching {
        val v = raw?.trim()?.replace("\\/", "/").orEmpty(); if (v.isBlank()) return@runCatching null; URI(base).resolve(v).toString()
    }.getOrNull()

    private fun host(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
    private fun looksAudio(url: String): Boolean = runCatching { URI(url).path.orEmpty().lowercase().substringAfterLast('.', "") in setOf("mp3","m4a","m4b","aac","ogg","opus","flac","m3u8") }.getOrDefault(false)
    private fun scanText(text: String?): List<String> {
        val clean = text.orEmpty().replace("\\/", "/")
        return Regex("https?://[^\\\"'<>\\s]+\\.(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE).findAll(clean).map { it.value }.distinct().take(500).toList()
    }
}
