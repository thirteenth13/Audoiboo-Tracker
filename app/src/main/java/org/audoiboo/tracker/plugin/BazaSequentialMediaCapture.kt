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

/** Detects the rendered Baza player playlist, including open shadow DOM, and activates every track. */
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

            fun normalizedMedia(): List<String> {
                val raw = snapshot().sortedWith(compareBy({ BazaKnigWebViewMediaCapture.trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                if (expectedTracks <= 0 || raw.size <= expectedTracks) return raw

                // The rendered playlist length is authoritative. Baza can preload one adjacent MP3
                // beyond the visible playlist, so prefer a complete contiguous 0..N-1 sequence
                // from one media directory when it is available.
                val byDirectory = raw.groupBy { url ->
                    runCatching { URI(url).resolve(".").toString() }.getOrDefault("")
                }
                val expectedNumbers = (0 until expectedTracks).toSet()
                val complete = byDirectory.values
                    .map { group ->
                        group.mapNotNull { url -> BazaKnigWebViewMediaCapture.trackNumber(url)?.let { it to url } }
                            .toMap()
                    }
                    .firstOrNull { numbered -> expectedNumbers.all(numbered::containsKey) }

                if (complete != null) {
                    diagnostics += "normalized=${raw.size}->${expectedTracks}"
                    return (0 until expectedTracks).mapNotNull(complete::get)
                }

                diagnostics += "normalization-skipped=${raw.size}/$expectedTracks"
                return raw
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = normalizedMedia()
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
                if (traversalDone && expectedTracks > 0 && snapshot().size >= expectedTracks) {
                    finish("playlist-complete")
                }
            }

            fun remember(raw: String?) {
                val value = raw?.trim()?.replace("\\/", "/").orEmpty()
                if (value.isBlank()) return
                val url = runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return
                if (!BazaKnigWebViewMediaCapture.isBookAudio(url)) return
                synchronized(found) { if (found.size < 350) found += url }
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
                    if (!message.isNullOrBlank() && diagnostics.size < 300) diagnostics += "js:$message"
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
                    listOf(1_500L, 3_000L, 5_000L, 8_000L, 12_000L, 18_000L, 26_000L, 34_000L, 40_000L).forEach { delay ->
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
              if (window.__audoibooBazaShadowPlaylist) return 'already';
              window.__audoibooBazaShadowPlaylist = true;

              const norm = s => String(s || '').replace(/\s+/g, ' ').trim();

              const roots = () => {
                const out = [];
                const seen = new Set();
                const addRoot = root => {
                  if (!root || seen.has(root)) return;
                  seen.add(root); out.push(root);
                  try {
                    root.querySelectorAll('*').forEach(e => {
                      try { if (e.shadowRoot) addRoot(e.shadowRoot); } catch (_) {}
                    });
                    root.querySelectorAll('iframe,frame').forEach(f => {
                      try { if (f.contentDocument) addRoot(f.contentDocument); } catch (_) {}
                    });
                  } catch (_) {}
                };
                addRoot(document);
                return out;
              };

              const all = selector => {
                const out = [];
                roots().forEach(r => { try { r.querySelectorAll(selector).forEach(e => out.push(e)); } catch (_) {} });
                return out;
              };

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
                    .slice(0, 2000).forEach(emit);
                } catch (_) {}
              };

              const scanMedia = () => {
                try {
                  roots().forEach(r => {
                    r.querySelectorAll('audio,source').forEach(e => {
                      emit(e.currentSrc); emit(e.src); emit(e.getAttribute('src'));
                    });
                    r.querySelectorAll('*').forEach(e => {
                      for (const a of Array.from(e.attributes || [])) {
                        if (/^(src|href|data-|onclick|onplay)/i.test(a.name)) scanText(a.value);
                      }
                    });
                    r.querySelectorAll('script').forEach(s => scanText(s.textContent || s.innerHTML));
                  });
                  performance.getEntriesByType('resource').forEach(e => emit(e.name));
                } catch (_) {}
              };

              document.addEventListener('click', e => {
                try {
                  const a = e.target?.closest?.('a[href]');
                  if (!a) return;
                  const href = String(a.getAttribute('href') || '').trim();
                  if (href && href !== '#' && !href.toLowerCase().startsWith('javascript:')) e.preventDefault();
                } catch (_) {}
              }, true);

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

              // Baza playlists are zero-based in the UI (00, 01, ...). Return -1 for
              // non-track nodes so track 00 can be represented as the numeric value 0.
              const exactNumber = e => {
                const t = norm(e.innerText || e.textContent);
                if (!/^0*\d{1,3}$/.test(t)) return -1;
                const n = Number(t);
                return n >= 0 && n < 350 ? n : -1;
              };

              const descendants = root => {
                const out = [];
                try { root.querySelectorAll('*').forEach(e => out.push(e)); } catch (_) {}
                return out;
              };

              const sequenceFor = root => {
                const nums = new Set();
                const own = exactNumber(root);
                if (own >= 0) nums.add(own);
                descendants(root).forEach(e => { const n = exactNumber(e); if (n >= 0) nums.add(n); });
                let count = 0;
                while (nums.has(count) && count < 350) count++;
                return count;
              };

              const smallestNumberNode = (root, n) => {
                const matches = descendants(root).filter(e => exactNumber(e) === n);
                if (exactNumber(root) === n) matches.unshift(root);
                if (!matches.length) return null;
                matches.sort((a,b) => descendants(a).length - descendants(b).length);
                return matches[0];
              };

              const findPlaylist = () => {
                let best = null;
                for (const r of roots()) {
                  const zeroes = [];
                  try { r.querySelectorAll('*').forEach(e => { if (exactNumber(e) === 0) zeroes.push(e); }); } catch (_) {}
                  for (const zero of zeroes.slice(0,160)) {
                    let node = zero;
                    for (let depth=0; depth<14 && node; depth++, node=node.parentElement) {
                      const count = sequenceFor(node);
                      if (!best || count > best.count) best = {root:node, count};
                    }
                    try {
                      const host = zero.getRootNode()?.host;
                      if (host) {
                        let h = host;
                        for (let depth=0; depth<8 && h; depth++, h=h.parentElement) {
                          const count = sequenceFor(h.shadowRoot || h);
                          if (!best || count > best.count) best = {root:h.shadowRoot || h, count};
                        }
                      }
                    } catch (_) {}
                  }
                }
                if (!best || best.count < 2) return null;
                const nodes = [];
                for (let n=0; n<best.count; n++) {
                  const node = smallestNumberNode(best.root, n);
                  if (!node) break;
                  nodes.push(node);
                }
                return nodes.length >= 2 ? {root:best.root, nodes} : null;
              };

              const activate = target => {
                try { target.scrollIntoView({block:'center',inline:'nearest'}); } catch (_) {}
                for (const type of ['pointerdown','mousedown','pointerup','mouseup','click']) {
                  try { target.dispatchEvent(new MouseEvent(type,{bubbles:true,cancelable:true,view:window})); } catch (_) {}
                }
                try { target.click(); } catch (_) {}
              };

              const clickable = (node, root) => {
                let e = node;
                for (let i=0; i<6 && e && e !== root; i++, e=e.parentElement) {
                  const attrs = [e.getAttribute?.('onclick'),e.getAttribute?.('role'),e.className,e.getAttribute?.('data-src'),e.getAttribute?.('data-url')]
                    .filter(Boolean).join(' ');
                  if (e.tagName === 'BUTTON' || e.tagName === 'A' || /button|track|item|play|audio|click/i.test(attrs)) return e;
                }
                return node.parentElement || node;
              };

              const tryExpand = () => {
                let clicked = 0;
                const candidates = all('button,[role=button],[aria-expanded],[onclick],a,span,div').filter(e => {
                  const t = norm(e.innerText || e.textContent);
                  if (t.length > 20) return false;
                  const attrs = [e.id,e.className,e.getAttribute?.('title'),e.getAttribute?.('aria-label'),e.getAttribute?.('data-action')]
                    .filter(Boolean).join(' ');
                  return /playlist|tracklist|track-list|audio-list|player-list|expand|collapse|toggle|list|menu|more/i.test(attrs) ||
                    e.getAttribute?.('aria-expanded') === 'false' ||
                    (!!e.querySelector?.('svg,i') && t.length <= 3);
                }).slice(0,50);
                [...new Set(candidates)].forEach(e => { try { e.click(); clicked++; } catch (_) {} });
                AudoibooBazaSequential.event('expand=' + clicked + ' roots=' + roots().length + ' shadow=' + all('*').filter(e => !!e.shadowRoot).length);
              };

              const startTraversal = playlist => {
                if (window.__audoibooBazaTraversalStarted) return;
                window.__audoibooBazaTraversalStarted = true;
                const tracks = playlist.nodes;
                AudoibooBazaSequential.playlist(tracks.length);
                AudoibooBazaSequential.event('playlist-found=' + tracks.length + ' roots=' + roots().length);
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
                  AudoibooBazaSequential.event('activate=' + n + '/' + tracks.length + ':' + String(target.tagName) + '.' + String(target.className || '').slice(0,45));
                  activate(target);
                  setTimeout(scanMedia, 260);
                  setTimeout(next, 800);
                };
                setTimeout(next, 300);
              };

              let attempts = 0;
              window.__audoibooBazaRescan = () => {
                scanMedia();
                if (window.__audoibooBazaTraversalStarted) return true;
                const playlist = findPlaylist();
                if (playlist) {
                  startTraversal(playlist);
                  return true;
                }
                attempts++;
                if (attempts === 2 || attempts === 4 || attempts === 6 || attempts === 8) tryExpand();
                AudoibooBazaSequential.event('playlist-wait=' + attempts + ' roots=' + roots().length + ' shadow=' + all('*').filter(e => !!e.shadowRoot).length + ' nums=' + all('*').filter(e => exactNumber(e) >= 0).length);
                return false;
              };

              try {
                const observe = () => roots().forEach(r => {
                  try {
                    if (r.__audoibooObserved) return;
                    r.__audoibooObserved = true;
                    new MutationObserver(() => {
                      if (!window.__audoibooBazaTraversalStarted) window.__audoibooBazaRescan();
                    }).observe(r instanceof Document ? r.documentElement : r,{childList:true,subtree:true});
                  } catch (_) {}
                });
                observe();
                setInterval(observe, 1200);
              } catch (_) {}

              return window.__audoibooBazaRescan();
            })();
        """.trimIndent()

        private val RESCAN = """
            (() => window.__audoibooBazaRescan ? window.__audoibooBazaRescan() : false)();
        """.trimIndent()
    }
}