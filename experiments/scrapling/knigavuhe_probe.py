from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright as sync_playwright_pw
from patchright.sync_api import sync_playwright as sync_playwright_pr

AUDIO_RE = re.compile(r"https?://[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\?[^\"'<>\s]*)?", re.I)
BOOK_ID_RE = re.compile(r"(?:/play/id/|/covers/|book[_-]?id[^0-9]{0,20})(\d{4,})", re.I)
ARTIFACT_DIR = Path(os.environ.get("KV_ARTIFACT_DIR", "artifacts/knigavuhe"))
HEADLESS = os.environ.get("KV_HEADLESS", "1").lower() not in {"0", "false", "no"}


@dataclass
class KnigavuheProbeResult:
    requests: int
    media: list[str]
    diagnostics: list[str]


def _is_book_audio(url: str) -> bool:
    parts = urlsplit(url)
    host = (parts.hostname or "").lower()
    path = parts.path.lower()
    return host.endswith("knigavuhe.org") and "/audio/" in path and path.endswith((".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac"))


def _extract_book_id(text: str) -> str | None:
    m = BOOK_ID_RE.search(text)
    return m.group(1) if m else None


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


def _save_debug(page, backend: str, phase: str, diagnostics: list[str]) -> None:
    try:
        ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
        base = ARTIFACT_DIR / f"{backend}-{phase}"
        page.screenshot(path=str(base.with_suffix(".png")), full_page=True)
        base.with_suffix(".html").write_text(page.content(), encoding="utf-8")
        diagnostics.append(f"{backend}:artifact:{base.name}")
    except Exception as exc:
        diagnostics.append(f"{backend}:artifact-error:{type(exc).__name__}")


def _probe_play_api(page, backend: str, diagnostics: list[str], remember) -> None:
    try:
        html = page.content().replace("\\/", "/")
        book_id = _extract_book_id(html)
        diagnostics.append(f"{backend}:book-id={book_id or 'miss'}")
        if not book_id:
            return
        result = page.evaluate(
            r"""async id => {
                const out = [];
                for (const method of ['GET','POST']) {
                    try {
                        const r = await fetch('/play/id/' + id, {
                            method,
                            credentials:'include',
                            headers:{'X-Requested-With':'XMLHttpRequest','Accept':'application/json,text/plain,*/*'}
                        });
                        const text = await r.text();
                        out.push({method,status:r.status,ctype:r.headers.get('content-type') || '',text:text.slice(0,12000)});
                    } catch(e) { out.push({method,error:String(e)}); }
                }
                return out;
            }""",
            book_id,
        )
        for item in result:
            diagnostics.append(f"{backend}:play-api:{item.get('method')}:{item.get('status', item.get('error',''))}:ctype={item.get('ctype','')}:len={len(item.get('text',''))}")
            text = str(item.get("text", "")).replace("\\/", "/").replace("&amp;", "&")
            compact = re.sub(r"\s+", " ", text).strip()
            if compact:
                diagnostics.append(f"{backend}:play-body:{compact[:900]}")
            for match in AUDIO_RE.finditer(text):
                remember(match.group(0), f"{backend}-play-api")
    except Exception as exc:
        diagnostics.append(f"{backend}:play-api-error:{type(exc).__name__}")


def _run_backend(sync_factory, backend: str, page_url: str, max_tracks: int, timeout_ms: int, remember) -> tuple[int, list[str]]:
    diagnostics: list[str] = []
    requests = 0
    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    har_path = ARTIFACT_DIR / f"{backend}.har"

    with sync_factory() as pw:
        browser = pw.chromium.launch(headless=HEADLESS, args=["--autoplay-policy=no-user-gesture-required"])
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36",
            locale="ru-RU",
            viewport={"width": 1440, "height": 1200},
            record_har_path=str(har_path),
            record_har_content="omit",
        )
        page = context.new_page()
        page.add_init_script(r"""() => {
            window.__oaiMedia = [];
            window.__oaiNet = [];
            const add = v => { try { if (typeof v === 'string' && /^https?:/i.test(v) && !window.__oaiMedia.includes(v)) window.__oaiMedia.push(v); } catch(e) {} };
            try {
                const NativeAudio = window.Audio;
                window.Audio = function(src) { const a = new NativeAudio(src); if (src) add(String(src)); return a; };
                window.Audio.prototype = NativeAudio.prototype;
            } catch(e) {}
            try {
                const d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (d && d.set && d.get) Object.defineProperty(HTMLMediaElement.prototype, 'src', {configurable:true, enumerable:d.enumerable, get:d.get, set:function(v){ add(String(v)); return d.set.call(this,v); }});
            } catch(e) {}
            try {
                const orig = Element.prototype.setAttribute;
                Element.prototype.setAttribute = function(name, value) { if ((this instanceof HTMLMediaElement || this.tagName === 'SOURCE') && String(name).toLowerCase() === 'src') add(String(value)); return orig.call(this,name,value); };
            } catch(e) {}
            try {
                const nativeFetch = window.fetch;
                window.fetch = function(input, init) { try { window.__oaiNet.push('fetch:' + (typeof input === 'string' ? input : input.url)); } catch(e) {} return nativeFetch.apply(this, arguments); };
            } catch(e) {}
            try {
                const open = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) { try { window.__oaiNet.push('xhr:' + method + ':' + url); } catch(e) {} return open.apply(this, arguments); };
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
                diagnostics.append(f"{backend}:network-play:{typ}:{url[:900]}")
            if typ.lower() == "media" or "/audio/" in url:
                remember(url, f"{backend}-cdp")

        session.on("Network.requestWillBeSent", on_request)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(2500)
        diagnostics.append(f"{backend}:mode={'headless' if HEADLESS else 'headful'}")
        _save_debug(page, backend, "loaded", diagnostics)
        _probe_play_api(page, backend, diagnostics, remember)

        for pattern in ("Понятно", "Слушать полностью"):
            try:
                button = page.get_by_text(re.compile(pattern, re.I)).first
                if button.count() and button.is_visible():
                    button.click(force=True, timeout=2500)
                    diagnostics.append(f"{backend}:click:{pattern}")
                    page.wait_for_timeout(900)
            except Exception as exc:
                diagnostics.append(f"{backend}:click-{pattern}:{type(exc).__name__}")

        boxes = _track_boxes(page)
        diagnostics.append(f"{backend}:track-boxes={len(boxes)}:{[b['text'] for b in boxes[:12]]}")
        for box in boxes[:max_tracks]:
            try:
                _click_box(page, box)
                page.wait_for_timeout(300)
            except Exception as exc:
                diagnostics.append(f"{backend}:track-click-error:{type(exc).__name__}:{box.get('text','')}")

        try:
            hooked = page.evaluate("() => Array.isArray(window.__oaiMedia) ? window.__oaiMedia : []")
            for url in hooked:
                remember(str(url), f"{backend}-hook")
            net = page.evaluate("() => Array.isArray(window.__oaiNet) ? window.__oaiNet : []")
            diagnostics.append(f"{backend}:hooked={len(hooked)}:net-hooks={len(net)}")
            diagnostics.extend(f"{backend}:net:{str(x)[:700]}" for x in net[-20:])
        except Exception as exc:
            diagnostics.append(f"{backend}:hook-error:{type(exc).__name__}")

        try:
            html = page.content().replace("\\/", "/")
            found = 0
            for match in AUDIO_RE.finditer(html):
                before = len(remember.__self__) if hasattr(remember, "__self__") else 0
                remember(match.group(0), f"{backend}-dom")
                found += 1
            diagnostics.append(f"{backend}:dom-audio-candidates={found}")
        except Exception as exc:
            diagnostics.append(f"{backend}:dom-error:{type(exc).__name__}")

        _save_debug(page, backend, "after-clicks", diagnostics)
        context.close()
        browser.close()

    return requests, diagnostics


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

    for backend, factory in (("playwright", sync_playwright_pw), ("patchright", sync_playwright_pr)):
        try:
            count, diag = _run_backend(factory, backend, page_url, max_tracks, timeout_ms, remember)
            requests += count
            diagnostics.extend(diag)
            if media:
                diagnostics.append(f"{backend}:success={len(media)}")
                break
        except Exception as exc:
            diagnostics.append(f"{backend}:backend-error:{type(exc).__name__}:{str(exc)[:400]}")

    return KnigavuheProbeResult(requests=requests, media=media, diagnostics=diagnostics)
