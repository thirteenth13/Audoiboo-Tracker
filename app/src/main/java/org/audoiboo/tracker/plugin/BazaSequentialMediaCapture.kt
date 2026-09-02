package org.audoiboo.tracker.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Captures Baza media and probes sequential sibling MP3 files from the first real track URL. */
class BazaSequentialMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 35_000L, onComplete: (Result) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val found = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val probing = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun snapshot(): List<String> = synchronized(found) { found.toList() }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = snapshot().sortedWith(compareBy({ BazaKnigWebViewMediaCapture.trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                diagnostics += reason
                diagnostics += "media=${media.size}"
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
                    webView.destroy()
                }
                onComplete(Result(pageUrl, media, diagnostics.toList()))
            }

            fun probeCandidate(url: String): Int = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 2_500
                    readTimeout = 2_500
                    requestMethod = "GET"
                    setRequestProperty("Range", "bytes=0-0")
                    setRequestProperty("Referer", pageUrl)
                    setRequestProperty("User-Agent", webView.settings.userAgentString)
                    setRequestProperty("Accept", "audio/mpeg,audio/*;q=0.9,*/*;q=0.1")
                }
                try {
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }.getOrDefault(-1)

            fun startSiblingProbe(seed: String) {
                if (!probing.compareAndSet(false, true)) return
                val uri = runCatching { URI(seed) }.getOrNull() ?: return
                val name = uri.path?.substringAfterLast('/').orEmpty()
                val match = Regex("^(\\d{1,4})\\.mp3$", RegexOption.IGNORE_CASE).matchEntire(name) ?: return
                val seedIndex = match.groupValues[1].toIntOrNull() ?: return
                val base = runCatching { uri.resolve(".") }.getOrNull() ?: return
                diagnostics += "probe-base=${base.host}${base.path}"

                Thread {
                    var consecutiveMisses = 0
                    var highestHit = -1
                    var checked = 0
                    val start = 0
                    val max = maxOf(seedIndex + 24, 80).coerceAtMost(160)

                    for (index in start..max) {
                        if (finished.get()) break
                        val candidate = base.resolve("$index.mp3").toString()
                        val code = if (candidate == seed) 206 else probeCandidate(candidate)
                        checked++
                        val ok = code in 200..399
                        if (ok) {
                            synchronized(found) { if (found.size < 350) found += candidate }
                            highestHit = index
                            consecutiveMisses = 0
                        } else {
                            consecutiveMisses++
                        }
                        if (highestHit >= 0 && consecutiveMisses >= 4 && index > highestHit) break
                    }

                    handler.post {
                        if (finished.get()) return@post
                        diagnostics += "probe-checked=$checked highest=$highestHit found=${snapshot().size}"
                        if (snapshot().size > 1) finish("sibling-probe")
                    }
                }.start()
            }

            fun remember(raw: String?) {
                val value = raw?.trim()?.replace("\\/", "/").orEmpty()
                if (value.isBlank()) return
                val url = runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return
                if (!BazaKnigWebViewMediaCapture.isBookAudio(url)) return
                val added = synchronized(found) {
                    if (found.size >= 350) false else found.add(url)
                }
                if (added && runCatching { URI(url).host?.endsWith("redirectto.cc") == true }.getOrDefault(false)) {
                    startSiblingProbe(url)
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

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val next = request?.url?.toString().orEmpty()
                    if (next.isNotBlank() && next != pageUrl) {
                        diagnostics += "blocked-nav"
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!BazaKnigWebViewMediaCapture.isAllowedPage(url) || finished.get()) return
                    diagnostics += "loaded"
                    view.evaluateJavascript(SCRIPT, null)
                    listOf(2_000L, 5_000L, 9_000L, 14_000L, 20_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(RESCAN, null) }, delay)
                    }
                }
            }

            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(35_000L))
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooBazaSequential"

        private val SCRIPT = """
            (() => {
              if (window.__audoibooBazaProbe) return 'already';
              window.__audoibooBazaProbe = true;
              const emit = raw => {
                try {
                  if (!raw) return;
                  const clean = String(raw).replaceAll('\\/','/').trim();
                  if (clean) AudoibooBazaSequential.media(new URL(clean, location.href).href);
                } catch (_) {}
              };
              const scanText = text => {
                try {
                  const value = String(text || '').replaceAll('\\/','/');
                  (value.match(/(?:https?:\/\/|\/\/|\/)[^\"'<>\s]+\.mp3(?:\?[^\"'<>\s]*)?/gi) || [])
                    .slice(0, 1500).forEach(emit);
                } catch (_) {}
              };
              window.__audoibooBazaRescan = () => {
                try {
                  document.querySelectorAll('audio,source').forEach(e => { emit(e.currentSrc); emit(e.src); emit(e.getAttribute('src')); });
                  document.querySelectorAll('*').forEach(e => {
                    for (const a of Array.from(e.attributes || [])) {
                      if (/^(src|href|data-|onclick|onplay)/i.test(a.name)) scanText(a.value);
                    }
                  });
                  document.querySelectorAll('script').forEach(s => scanText(s.textContent || s.innerHTML));
                  scanText(document.documentElement.innerHTML);
                  performance.getEntriesByType('resource').forEach(e => emit(e.name));
                } catch (_) {}
                return true;
              };
              const nativeFetch = window.fetch;
              if (nativeFetch) window.fetch = function(...args) {
                args.forEach(scanText);
                return nativeFetch.apply(this,args).then(r => {
                  try { r.clone().text().then(scanText).catch(() => {}); } catch (_) {}
                  return r;
                });
              };
              const nativeOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(...args) {
                args.forEach(scanText);
                this.addEventListener('load', () => { try { scanText(this.responseText); } catch (_) {} });
                return nativeOpen.apply(this,args);
              };
              const src = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
              if (src && src.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                configurable: src.configurable, enumerable: src.enumerable, get: src.get,
                set(v) { emit(v); return src.set.call(this, v); }
              });
              return window.__audoibooBazaRescan();
            })();
        """.trimIndent()

        private val RESCAN = """
            (() => window.__audoibooBazaRescan ? window.__audoibooBazaRescan() : false)();
        """.trimIndent()
    }
}
