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
import java.net.URI
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/** Detects the rendered Baza player playlist and activates every track dynamically. */
class BazaSequentialMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 45_000L, onComplete: (Result) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val found = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)
            var expectedTracks = -1
            var traversalDone = false

            fun snapshot(): List<String> = synchronized(found) { found.toList() }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = snapshot().sortedWith(compareBy({ BazaKnigWebViewMediaCapture.trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                diagnostics += reason
                diagnostics += "expected=$expectedTracks"
                diagnostics += "media=${media.size}"
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.removeJavascriptInterface(BRIDGE)
                    webView.destroy()
                }
                onComplete(Result(pageUrl, media, diagnostics.toList()))
            }

            fun maybeFinish() {
                if (!traversalDone || expectedTracks <= 0) return
                if (snapshot().size >= expectedTracks) finish("playlist-complete")
            }

            fun remember(raw: String?) {
                val value = raw?.trim()?.replace("\\/", "/").orEmpty()
                if (value.isBlank()) return
                val url = runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return
                if (!BazaKnigWebViewMediaCapture.isBookAudio(url)) return
                synchronized(found) {
                    if (found.size < 350) found += url
                }
                maybeFinish()
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
                @JavascriptInterface fun playlist(count: Int) = handler.post {
                    if (count > expectedTracks) {
                        expectedTracks = count
                        diagnostics += "js:playlist=$count"
                    }
                }
                @JavascriptInterface fun done(count: Int) = handler.post {
                    if (count > expectedTracks) expectedTracks = count
                    traversalDone = true
                    diagnostics += "js:playlist-done=$count captured=${snapshot().size}"
                    maybeFinish()
                }
                @JavascriptInterface fun event(message: String?) = handler.post {
                    if (!message.isNullOrBlank() && diagnostics.size < 240) diagnostics += "js:$message"
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
                    listOf(1_500L, 3_000L, 5_000L, 8_000L, 12_000L, 18_000L, 26_000L, 34_000L).forEach { delay ->
                        handler.postDelayed({ if (!finished.get()) view.evaluateJavascript(RESCAN, null) }, delay)
                    }
                }
            }

            handler.postDelayed({ finish("timeout") }, timeoutMs.coerceAtLeast(45_000L))
            webView.loadUrl(pageUrl)
        }
    }

    companion object {
        private const val BRIDGE = "AudoibooBazaSequential"

        private val SCRIPT = """
            (() => {
              if (window.__audoibooBazaPlaylist) return 'already';
              window.__audoibooBazaPlaylist = true;

              document.addEventListener('click', e => {
                try {
                  const a = e.target && e.target.closest ? e.target.closest('a[href]') : null;
                  if (!a) return;
                  const href = String(a.getAttribute('href') || '').trim();
                  if (href && href !== '#' && !href.toLowerCase().startsWith('javascript:')) e.preventDefault();
                } catch (_) {}
              }, true);

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
              const scanMedia = () => {
                try {
                  document.querySelectorAll('audio,source').forEach(e => {
                    emit(e.currentSrc); emit(e.src); emit(e.getAttribute('src'));
                  });
                  document.querySelectorAll('*').forEach(e => {
                    for (const a of Array.from(e.attributes || [])) {
                      if (/^(src|href|data-|onclick|onplay)/i.test(a.name)) scanText(a.value);
                    }
                  });
                  document.querySelectorAll('script').forEach(s => scanText(s.textContent || s.innerHTML));
                  try { performance.getEntriesByType('resource').forEach(e => emit(e.name)); } catch (_) {}
                } catch (_) {}
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

              const norm = s => String(s || '').replace(/\s+/g, ' ').trim();
              const exactNumber = e => {
                const t = norm(e.innerText || e.textContent);
                if (!/^\d{1,3}$/.test(t)) return 0;
                const n = Number(t);
                return n >= 1 && n <= 350 ? n : 0;
              };

              const smallestNumberNode = (root, n) => {
                const matches = [...root.querySelectorAll('li,div,span,a,button,p')]
                  .filter(e => exactNumber(e) === n);
                if (!matches.length) return null;
                matches.sort((a,b) => a.querySelectorAll('*').length - b.querySelectorAll('*').length);
                return matches[0];
              };

              const sequenceFor = root => {
                const nums = new Set();
                root.querySelectorAll('li,div,span,a,button,p').forEach(e => {
                  const n = exactNumber(e); if (n) nums.add(n);
                });
                let count = 0;
                while (nums.has(count + 1) && count < 350) count++;
                return count;
              };

              const findPlaylist = () => {
                const ones = [...document.querySelectorAll('li,div,span,a,button,p')]
                  .filter(e => exactNumber(e) === 1);
                let bestRoot = null, bestCount = 0;
                for (const one of ones.slice(0, 80)) {
                  let root = one;
                  for (let depth = 0; depth < 9 && root; depth++, root = root.parentElement) {
                    const count = sequenceFor(root);
                    if (count > bestCount) {
                      bestCount = count;
                      bestRoot = root;
                    }
                  }
                }
                if (!bestRoot || bestCount < 2) return null;
                const nodes = [];
                for (let n = 1; n <= bestCount; n++) {
                  const node = smallestNumberNode(bestRoot, n);
                  if (!node) break;
                  nodes.push(node);
                }
                return nodes.length >= 2 ? {root: bestRoot, nodes} : null;
              };

              const tryExpand = () => {
                try {
                  const audio = document.querySelector('audio');
                  let root = audio;
                  for (let i=0; i<6 && root?.parentElement; i++) root = root.parentElement;
                  if (!root) root = document.body;
                  const controls = [...root.querySelectorAll('[aria-expanded],button,[role=button],a,div,span')];
                  const scored = controls.filter(e => {
                    const attrs = [e.id,e.className,e.getAttribute('title'),e.getAttribute('aria-label'),e.getAttribute('data-action')]
                      .filter(Boolean).join(' ');
                    return /playlist|tracklist|track-list|audio-list|player-list|expand|collapse|toggle/i.test(attrs) || e.getAttribute('aria-expanded') === 'false';
                  }).slice(0,8);
                  scored.forEach(e => { try { e.click(); } catch (_) {} });
                  if (scored.length) AudoibooBazaSequential.event('expand=' + scored.length);
                } catch (_) {}
              };

              const clickable = (node, root) => {
                let e = node;
                for (let i=0; i<4 && e && e !== root; i++, e=e.parentElement) {
                  const attrs = [e.getAttribute?.('onclick'),e.getAttribute?.('role'),e.className,e.getAttribute?.('data-src'),e.getAttribute?.('data-url')]
                    .filter(Boolean).join(' ');
                  if (e.tagName === 'BUTTON' || e.tagName === 'A' || /button|track|item|play|audio|click/i.test(attrs)) return e;
                }
                return node.parentElement || node;
              };

              const activate = target => {
                try { target.scrollIntoView({block:'center',inline:'nearest'}); } catch (_) {}
                for (const type of ['pointerdown','mousedown','pointerup','mouseup','click']) {
                  try { target.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window})); } catch (_) {}
                }
                try { target.click(); } catch (_) {}
              };

              const startTraversal = playlist => {
                if (window.__audoibooBazaTraversalStarted) return;
                window.__audoibooBazaTraversalStarted = true;
                const tracks = playlist.nodes;
                AudoibooBazaSequential.playlist(tracks.length);
                AudoibooBazaSequential.event('playlist-root=' + String(playlist.root.tagName) + '.' + String(playlist.root.className || '').slice(0,80));
                let index = 0;
                const next = () => {
                  if (index >= tracks.length) {
                    scanMedia();
                    AudoibooBazaSequential.done(tracks.length);
                    return;
                  }
                  const n = index + 1;
                  const node = tracks[index++];
                  const target = clickable(node, playlist.root);
                  AudoibooBazaSequential.event('activate=' + n + '/' + tracks.length + ':' + String(target.tagName) + '.' + String(target.className || '').slice(0,50));
                  activate(target);
                  setTimeout(scanMedia, 180);
                  setTimeout(next, 520);
                };
                setTimeout(next, 300);
              };

              let attempts = 0;
              window.__audoibooBazaRescan = () => {
                scanMedia();
                if (window.__audoibooBazaTraversalStarted) return true;
                const playlist = findPlaylist();
                if (playlist) {
                  AudoibooBazaSequential.event('playlist-found=' + playlist.nodes.length);
                  startTraversal(playlist);
                  return true;
                }
                attempts++;
                if (attempts === 2 || attempts === 5) tryExpand();
                AudoibooBazaSequential.event('playlist-wait=' + attempts);
                return false;
              };

              try { new MutationObserver(() => { if (!window.__audoibooBazaTraversalStarted) window.__audoibooBazaRescan(); }).observe(document.documentElement,{childList:true,subtree:true}); } catch (_) {}
              return window.__audoibooBazaRescan();
            })();
        """.trimIndent()

        private val RESCAN = """
            (() => window.__audoibooBazaRescan ? window.__audoibooBazaRescan() : false)();
        """.trimIndent()
    }
}
