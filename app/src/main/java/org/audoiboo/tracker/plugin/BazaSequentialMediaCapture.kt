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

/** Sequentially activates Baza player rows while preventing accidental page navigation. */
class BazaSequentialMediaCapture(private val context: Context) {
    data class Result(val pageUrl: String, val mediaUrls: List<String>, val diagnostics: List<String>)

    fun capture(pageUrl: String, timeoutMs: Long = 35_000L, onComplete: (Result) -> Unit) {
        Handler(Looper.getMainLooper()).post {
            val found = LinkedHashSet<String>()
            val diagnostics = mutableListOf<String>()
            val finished = AtomicBoolean(false)
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            fun remember(raw: String?) {
                val value = raw?.trim()?.replace("\\/", "/").orEmpty()
                if (value.isBlank()) return
                val url = runCatching { URI(pageUrl).resolve(value).toString() }.getOrNull() ?: return
                if (!BazaKnigWebViewMediaCapture.isBookAudio(url)) return
                synchronized(found) { if (found.size < 350) found += url }
            }

            fun finish(reason: String) {
                if (!finished.compareAndSet(false, true)) return
                handler.removeCallbacksAndMessages(null)
                val media = synchronized(found) {
                    found.toList().sortedWith(compareBy({ BazaKnigWebViewMediaCapture.trackNumber(it) ?: Int.MAX_VALUE }, { it }))
                }
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
                    if (!message.isNullOrBlank() && diagnostics.size < 220) diagnostics += "js:$message"
                }
            }, BRIDGE)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    remember(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val next = request?.url?.toString().orEmpty()
                    if (next.isNotBlank() && next != pageUrl && BazaKnigWebViewMediaCapture.isAllowedPage(next)) {
                        diagnostics += "blocked-nav"
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!BazaKnigWebViewMediaCapture.isAllowedPage(url) || finished.get()) return
                    diagnostics += "loaded"
                    view.evaluateJavascript(SCRIPT, null)
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
              if (window.__audoibooBazaSequential) return 'already';
              window.__audoibooBazaSequential = true;

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
                    .slice(0, 1200).forEach(emit);
                } catch (_) {}
              };
              const scan = () => {
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
                  performance.getEntriesByType('resource').forEach(e => emit(e.name));
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
              const visible = e => {
                const r = e.getBoundingClientRect(), s = getComputedStyle(e);
                return r.width > 0 && r.height > 0 && s.display !== 'none' && s.visibility !== 'hidden';
              };
              const ownLabel = e => {
                const t = norm(e.innerText || e.textContent);
                if (!t || t.length > 180) return false;
                return /^\d{1,3}(?:[\s._:)-]+.*)?$/i.test(t) || /(?:трек|глава|часть)\s*\d+/i.test(t);
              };
              const playerish = e => {
                const attrs = [e.id, e.className, e.getAttribute('role'), e.getAttribute('onclick'), e.getAttribute('data-src'), e.getAttribute('data-url'), e.getAttribute('data-file')]
                  .filter(Boolean).join(' ');
                return /track|playlist|audio|player|play|chapter|part|item|episode|jp-/i.test(attrs) || !!e.querySelector('audio,[data-src],[data-url],[data-file],[onclick],button,[role=button]');
              };

              const all = [...document.querySelectorAll('button,a,li,div,span')].filter(visible);
              let tracks = all.filter(e => ownLabel(e) && playerish(e))
                .filter(e => ![...e.children].some(c => visible(c) && ownLabel(c) && playerish(c)));
              if (tracks.length < 2) {
                tracks = all.filter(e => ownLabel(e))
                  .filter(e => ![...e.children].some(c => visible(c) && ownLabel(c)));
              }
              tracks = tracks.filter(e => {
                const t = norm(e.innerText || e.textContent);
                return !/(FAQ|Правила сайта|Политика конфиденциальности|Аудиокниги слушать онлайн|©\s*20\d\d)/i.test(t);
              }).slice(0, 160);

              AudoibooBazaSequential.event('tracks=' + tracks.length);
              tracks.slice(0, 12).forEach((e,i) => AudoibooBazaSequential.event('track-' + (i+1) + ':' + norm(e.innerText || e.textContent).slice(0,70)));

              const activate = e => {
                try { e.scrollIntoView({block:'center', inline:'nearest'}); } catch (_) {}
                const candidates = [e.querySelector('button,[role=button],[onclick]'), e].filter(Boolean);
                for (const target of candidates) {
                  try { target.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,view:window})); } catch (_) {}
                  try { target.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,view:window})); } catch (_) {}
                  try { target.click(); } catch (_) {}
                }
              };

              const signature = () => {
                try {
                  return [...document.querySelectorAll('audio,source')]
                    .map(e => e.currentSrc || e.src || e.getAttribute('src') || '').join('|');
                } catch (_) { return ''; }
              };

              let index = 0;
              let lastSignature = signature();
              const next = () => {
                if (index >= tracks.length) {
                  scan();
                  AudoibooBazaSequential.event('tracks-done=' + index);
                  return;
                }
                const before = signature();
                const n = index++;
                AudoibooBazaSequential.event('activate=' + (n + 1) + '/' + tracks.length);
                activate(tracks[n]);

                let polls = 0;
                const poll = () => {
                  scan();
                  const now = signature();
                  if (now && now !== before && now !== lastSignature) {
                    lastSignature = now;
                    AudoibooBazaSequential.event('changed=' + (n + 1));
                    setTimeout(next, 300);
                    return;
                  }
                  if (++polls >= 12) {
                    AudoibooBazaSequential.event('settled=' + (n + 1));
                    setTimeout(next, 250);
                    return;
                  }
                  setTimeout(poll, 180);
                };
                setTimeout(poll, 120);
              };

              scan();
              setTimeout(next, 500);
              return 'started';
            })();
        """.trimIndent()
    }
}
