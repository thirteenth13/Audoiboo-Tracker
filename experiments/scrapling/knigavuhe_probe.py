from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import sync_playwright

AUDIO_RE = re.compile(r"https?://[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\?[^\"'<>\s]*)?", re.I)
REL_AUDIO_RE = re.compile(r"(?:https?:)?//[^\"'<>\s]+/audio/[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\?[^\"'<>\s]*)?|/[^\"'<>\s]*/audio/[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\?[^\"'<>\s]*)?", re.I)
BOOK_ID_RE = re.compile(r"(?:/covers/|/play/id/)(\d+)", re.I)


@dataclass
class KnigavuheProbeResult:
    requests: int
    media: list[str]
    diagnostics: list[str]


def _is_book_audio(url: str) -> bool:
    host = (urlsplit(url).hostname or "").lower()
    path = urlsplit(url).path.lower()
    return host.endswith("knigavuhe.org") and "/audio/" in path and path.endswith((".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac"))


def _track_boxes(page) -> list[dict]:
    return page.evaluate(r"""() => {
        const norm = s => (s || '').replace(/\s+/g, ' ').trim();
        const out = [];
        for (const el of Array.from(document.querySelectorAll('body *'))) {
            const t = norm(el.innerText || el.textContent);
            if (!t || t.length > 180) continue;
            if (!(/_\d+(?:\s|$)/.test(t) || /^\d{2}(?:\s+\d{1,2}:\d{2})?$/.test(t))) continue;
            if (Array.from(el.children).some(c => {
                const ct = norm(c.innerText || c.textContent);
                return /_\d+(?:\s|$)/.test(ct) || /^\d{2}(?:\s+\d{1,2}:\d{2})?$/.test(ct);
            })) continue;
            const r = el.getBoundingClientRect(), st = getComputedStyle(el);
            if (r.width < 5 || r.height < 5 || r.height > 150 || st.display === 'none' || st.visibility === 'hidden') continue;
            out.push({text:t.slice(0,160), x:r.x, y:r.y, width:r.width, height:r.height, tag:el.tagName, cls:String(el.className || '').slice(0,120)});
            if (out.length >= 80) break;
        }
        return out;
    }""")


def _click_box(page, box: dict) -> None:
    page.mouse.move(box["x"] + min(max(14.0, box["width"] * 0.12), box["width"] / 2), box["y"] + box["height"] / 2)
    page.mouse.down()
    page.wait_for_timeout(35)
    page.mouse.up()


def _book_id(page) -> str | None:
    try:
        html = page.content()
    except Exception:
        return None
    m = BOOK_ID_RE.search(html)
    return m.group(1) if m else None


def _probe_play_endpoint(page, page_url: str, diagnostics: list[str], remember) -> None:
    book_id = _book_id(page)
    if not book_id:
        diagnostics.append("kv-play:book-id-miss")
        return
    endpoint = urljoin(page_url, f"/play/id/{book_id}")
    diagnostics.append(f"kv-play:id={book_id}")

    for method in ("GET", "POST"):
        try:
            result = page.evaluate(
                r"""async ({url, method}) => {
                    try {
                        const r = await fetch(url, {
                            method,
                            credentials: 'include',
                            headers: {
                                'X-Requested-With': 'XMLHttpRequest',
                                'Accept': 'application/json,text/plain,*/*'
                            }
                        });
                        const text = await r.text();
                        return {ok:true, status:r.status, type:r.headers.get('content-type') || '', text};
                    } catch (e) {
                        return {ok:false, error:String(e)};
                    }
                }""",
                {"url": endpoint, "method": method},
            )
        except Exception as exc:
            diagnostics.append(f"kv-play-{method.lower()}:eval-error:{type(exc).__name__}")
            continue

        if not result.get("ok"):
            diagnostics.append(f"kv-play-{method.lower()}:fetch-error:{result.get('error','')[:180]}")
            continue

        text = str(result.get("text", "")).replace("\\/", "/").replace("&amp;", "&")
        compact = re.sub(r"\s+", " ", text).strip()
        diagnostics.append(
            f"kv-play-{method.lower()}:{result.get('status')}:ctype={result.get('type','')}:len={len(text)}:body={compact[:1400]}"
        )

        found = 0
        for match in REL_AUDIO_RE.finditer(text):
            raw = match.group(0)
            if raw.startswith("//"):
                raw = "https:" + raw
            elif raw.startswith("/"):
                raw = urljoin(page_url, raw)
            before = len(getattr(remember, "_seen", [])) if hasattr(remember, "_seen") else None
            remember(raw, f"play-{method.lower()}")
            found += 1
        if found:
            diagnostics.append(f"kv-play-{method.lower()}-audio-candidates={found}")
            return


def capture(page_url: str, max_tracks: int = 40, timeout_ms: int = 30000) -> KnigavuheProbeResult:
    media: list[str] = []
    seen: set[str] = set()
    diagnostics: list[str] = []
    requests = 0

    def remember(url: str, source: str) -> None:
        if _is_book_audio(url) and url not in seen:
            seen.add(url)
            media.append(url)
            diagnostics.append(f"kv-audio-{source}:{url}")

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36",
            locale="ru-RU",
            viewport={"width": 1440, "height": 1200},
        )
        page = context.new_page()

        page.add_init_script(r"""() => {
            window.__oaiMedia = [];
            const add = v => { try { if (typeof v === 'string' && /^https?:/i.test(v) && !window.__oaiMedia.includes(v)) window.__oaiMedia.push(v); } catch(e) {} };
            try {
                const d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (d && d.set && d.get) Object.defineProperty(HTMLMediaElement.prototype, 'src', {configurable:true, enumerable:d.enumerable, get:d.get, set:function(v){ add(String(v)); return d.set.call(this,v); }});
            } catch(e) {}
            try {
                const orig = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) { if ((this instanceof HTMLMediaElement || this.tagName === 'SOURCE') && String(name).toLowerCase() === 'src') add(String(value)); return orig.call(this,name,value); };
            } catch(e) {}
        }""")

        session = context.new_cdp_session(page)
        session.send("Network.enable", {})

        def on_request(params) -> None:
            nonlocal requests
            requests += 1
            req = params.get("request") or {}
            url = str(req.get("url", ""))
            typ = str(params.get("type", ""))
            if "/play/id/" in url:
                diagnostics.append(f"kv-network-play:{typ}:{url}")
            if typ.lower() == "media" or "/audio/" in url:
                remember(url, "cdp")

        session.on("Network.requestWillBeSent", on_request)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(2200)

        # First call the same public player endpoint observed in a normal browser,
        # from inside the page so same-origin cookies/headers are preserved.
        _probe_play_endpoint(page, page_url, diagnostics, remember)
        page.wait_for_timeout(500)

        try:
            button = page.get_by_text(re.compile("Слушать полностью", re.I)).first
            if button.count() and button.is_visible():
                button.click(force=True, timeout=2000)
                diagnostics.append("kv-expand:playwright-click")
                page.wait_for_timeout(1200)
        except Exception as exc:
            diagnostics.append(f"kv-expand:{type(exc).__name__}")

        boxes = _track_boxes(page)
        diagnostics.append(f"kv-track-boxes={len(boxes)}:{[b['text'] for b in boxes[:12]]}")
        for box in boxes[:max_tracks]:
            try:
                _click_box(page, box)
                page.wait_for_timeout(350)
            except Exception as exc:
                diagnostics.append(f"kv-click-error:{type(exc).__name__}:{box.get('text','')}")

        try:
            hooked = page.evaluate("() => Array.isArray(window.__oaiMedia) ? window.__oaiMedia : []")
            for url in hooked:
                remember(str(url), "hook")
            diagnostics.append(f"kv-hooked={len(hooked)}")
        except Exception as exc:
            diagnostics.append(f"kv-hook-error:{type(exc).__name__}")

        try:
            html = page.content().replace("\\/", "/")
            found = 0
            for match in AUDIO_RE.finditer(html):
                url = match.group(0)
                before = len(media)
                remember(url, "dom")
                if len(media) > before:
                    found += 1
            diagnostics.append(f"kv-dom-audio={found}")
        except Exception as exc:
            diagnostics.append(f"kv-dom-error:{type(exc).__name__}")

        context.close()
        browser.close()

    return KnigavuheProbeResult(requests=requests, media=media, diagnostics=diagnostics)
