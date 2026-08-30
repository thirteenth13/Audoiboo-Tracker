from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import sync_playwright

AUDIO_EXTENSIONS = (".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac")
FILES_RE = re.compile(r"(?:https?://[^\"'<>\s]+)?/files/\d+(?:\?[^\"'<>\s]*)?", re.I)
AUDIO_RE = re.compile(r"https?://[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\?[^\"'<>\s]*)?", re.I)


@dataclass
class BrowserResolveResult:
    clicks: int
    redirects: int
    media: list[str]
    resolver_urls: list[str]
    diagnostics: list[str]


def _is_audio_url(url: str) -> bool:
    return urlsplit(url).path.lower().endswith(AUDIO_EXTENSIONS)


def redirect_target(response_url: str, status: int, headers: dict[str, str]) -> str | None:
    if status not in (301, 302, 303, 307, 308):
        return None
    if "poleknig.com" not in (urlsplit(response_url).hostname or ""):
        return None
    if not urlsplit(response_url).path.startswith("/files/"):
        return None
    normalized = {str(k).lower(): str(v) for k, v in headers.items()}
    location = normalized.get("location")
    if not location:
        return None
    target = urljoin(response_url, location)
    return target if _is_audio_url(target) else None


def extract_embedded_urls(text: str, page_url: str) -> tuple[list[str], list[str]]:
    cleaned = (text or "").replace("\\/", "/").replace("&amp;", "&")
    resolvers: list[str] = []
    media: list[str] = []
    for match in FILES_RE.finditer(cleaned):
        url = urljoin(page_url, match.group(0))
        if url not in resolvers:
            resolvers.append(url)
    for match in AUDIO_RE.finditer(cleaned):
        url = match.group(0)
        if url not in media:
            media.append(url)
    return resolvers, media


def _player_snapshot(page) -> dict:
    return page.evaluate(r"""() => {
        const norm = s => (s || '').replace(/\s+/g, ' ').trim();
        const rows = Array.from(document.querySelectorAll('body *')).filter(el => /^\d{2}$/.test(norm(el.innerText || el.textContent)));
        const unique = [];
        for (const el of rows) {
            if (Array.from(el.children).some(c => /^\d{2}$/.test(norm(c.innerText || c.textContent)))) continue;
            const attrs = {};
            for (const a of el.attributes || []) {
                if (/^(data-|href|src|onclick|id|class)/i.test(a.name)) attrs[a.name] = String(a.value).slice(0, 500);
            }
            unique.push({text:norm(el.innerText || el.textContent), tag:el.tagName, attrs, html:el.outerHTML.slice(0, 1600)});
            if (unique.length >= 20) break;
        }
        const scripts = Array.from(document.scripts).map(s => s.textContent || '').filter(t => /\/files\/|\.mp3|playlist|track|audio/i.test(t)).map(t => t.slice(0, 16000)).slice(0, 12);
        const audio = Array.from(document.querySelectorAll('audio,source')).map(el => ({tag:el.tagName, src:el.src || el.getAttribute('src') || '', currentSrc:el.currentSrc || '', html:el.outerHTML.slice(0,1200)})).slice(0,12);
        const storage = {};
        try { for (let i=0;i<localStorage.length;i++) { const k=localStorage.key(i); if (/track|audio|play|file/i.test(k)) storage['local:'+k]=String(localStorage.getItem(k)).slice(0,2000); } } catch(e) {}
        try { for (let i=0;i<sessionStorage.length;i++) { const k=sessionStorage.key(i); if (/track|audio|play|file/i.test(k)) storage['session:'+k]=String(sessionStorage.getItem(k)).slice(0,2000); } } catch(e) {}
        return {rows:unique, scripts, audio, storage, html:document.documentElement.outerHTML};
    }""")


def _snapshot_urls(snapshot: dict, page_url: str) -> tuple[list[str], list[str]]:
    chunks = [snapshot.get("html", ""), *snapshot.get("scripts", [])]
    for audio in snapshot.get("audio", []):
        chunks.extend([audio.get("src", ""), audio.get("currentSrc", ""), audio.get("html", "")])
    chunks.extend(snapshot.get("storage", {}).values())
    return extract_embedded_urls("\n".join(chunks), page_url)


def _click_track(page, label: str) -> bool:
    try:
        return bool(page.evaluate(r"""label => {
            const norm = s => (s || '').replace(/\s+/g, ' ').trim();
            const candidates = Array.from(document.querySelectorAll('body *')).filter(el => {
                if (norm(el.innerText || el.textContent) !== label) return false;
                return !Array.from(el.children).some(c => norm(c.innerText || c.textContent) === label);
            });
            if (!candidates.length) return false;
            candidates.sort((a, b) => {
                const ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
                return (ar.width * ar.height) - (br.width * br.height);
            });
            const el = candidates[0];
            const clickable = el.closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]') || el;
            clickable.scrollIntoView({block: 'center'});
            clickable.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
            clickable.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
            clickable.click();
            return true;
        }""", label))
    except Exception:
        return False


def resolve(page_url: str, max_tracks: int = 60, timeout_ms: int = 30000) -> BrowserResolveResult:
    media: list[str] = []
    seen_media: set[str] = set()
    resolver_urls: list[str] = []
    seen_resolvers: set[str] = set()
    diagnostics: list[str] = []
    redirects = 0
    clicks = 0

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36", locale="ru-RU")
        page = context.new_page()
        page.set_default_timeout(1500)

        def route_handler(route) -> None:
            request_url = route.request.url
            if _is_audio_url(request_url) and "poleknig.com/storage/" in request_url:
                route.abort()
            else:
                route.continue_()
        page.route("**/*", route_handler)

        def remember_resolver(url: str) -> None:
            if url not in seen_resolvers:
                seen_resolvers.add(url); resolver_urls.append(url)
        def remember_media(url: str) -> None:
            if url not in seen_media:
                seen_media.add(url); media.append(url)

        def on_request(request) -> None:
            try:
                u = str(request.url)
                if "poleknig.com" in (urlsplit(u).hostname or "") and urlsplit(u).path.startswith("/files/"):
                    remember_resolver(u)
            except Exception: pass

        def on_response(response) -> None:
            nonlocal redirects
            try:
                u = str(response.url); status = int(response.status); headers = dict(response.headers or {}); path = urlsplit(u).path
                if "poleknig.com" in (urlsplit(u).hostname or "") and path.startswith("/files/"):
                    remember_resolver(u); target = redirect_target(u, status, headers)
                    diagnostics.append(f"{path}:{status}:location={'yes' if headers.get('location') else 'no'}")
                    if target: redirects += 1; remember_media(target)
                elif _is_audio_url(u) and "poleknig.com/storage/" in u: remember_media(u)
            except Exception as exc: diagnostics.append(f"response-error:{type(exc).__name__}")

        page.on("request", on_request); page.on("response", on_response)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms); page.wait_for_timeout(1000)

        try:
            snapshot = _player_snapshot(page)
            rs, ms = _snapshot_urls(snapshot, page_url)
            for u in rs: remember_resolver(u)
            for u in ms: remember_media(u)
            diagnostics += [f"state-rows={len(snapshot.get('rows', []))}", f"state-scripts={len(snapshot.get('scripts', []))}", f"state-audio={len(snapshot.get('audio', []))}", f"state-resolvers={len(rs)}", f"state-media={len(ms)}"]
            for row in snapshot.get("rows", [])[:8]: diagnostics.append(f"row={row.get('text')}:{row.get('tag')}:{str(row.get('attrs', {}))[:500]}")
            for a in snapshot.get("audio", [])[:3]: diagnostics.append(f"audio-before=src:{a.get('src','')[:700]} current:{a.get('currentSrc','')[:700]}")
        except Exception as exc: diagnostics.append(f"state-error:{type(exc).__name__}")

        misses = 0
        for number in range(1, max_tracks + 1):
            label = f"{number:02d}"; before_r = len(resolver_urls); before_m = len(media)
            if not _click_track(page, label):
                misses += 1
                if misses >= 3: break
                continue
            clicks += 1; misses = 0; page.wait_for_timeout(250)
            try:
                snap = _player_snapshot(page); rs, ms = _snapshot_urls(snap, page_url)
                for u in rs: remember_resolver(u)
                for u in ms: remember_media(u)
                audio_srcs = [a.get('currentSrc') or a.get('src') or '' for a in snap.get('audio', [])]
                diagnostics.append(f"after-{label}:resolvers={len(rs)} media={len(ms)} audio={str(audio_srcs)[:900]}")
                new_rs = [u for u in rs if u not in seen_resolvers]
                if new_rs: diagnostics.append(f"after-{label}:new={str(new_rs)[:1200]}")
            except Exception as exc: diagnostics.append(f"after-{label}:snapshot-error:{type(exc).__name__}")
            waited = 0
            while waited < 450 and len(media) == before_m and len(resolver_urls) == before_r:
                page.wait_for_timeout(100); waited += 100

        diagnostics.insert(0, f"clicks={clicks}"); diagnostics.insert(1, f"resolvers={len(resolver_urls)}"); diagnostics.insert(2, f"redirects={redirects}")
        context.close(); browser.close()
    return BrowserResolveResult(clicks=clicks, redirects=redirects, media=media, resolver_urls=resolver_urls, diagnostics=diagnostics)
