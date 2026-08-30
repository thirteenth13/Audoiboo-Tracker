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
    """Find public resolver/media URLs already embedded in player markup or JS."""
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
    """Return bounded, non-cookie diagnostics from the visible player and scripts."""
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
            unique.push({text:norm(el.innerText || el.textContent), tag:el.tagName, attrs, html:el.outerHTML.slice(0, 1200)});
            if (unique.length >= 12) break;
        }
        const scripts = Array.from(document.scripts).map(s => s.textContent || '').filter(t => /\/files\/|\.mp3|playlist|track|audio/i.test(t)).map(t => t.slice(0, 12000)).slice(0, 12);
        const audio = Array.from(document.querySelectorAll('audio,source')).map(el => ({tag:el.tagName, src:el.src || el.getAttribute('src') || '', html:el.outerHTML.slice(0,800)})).slice(0,12);
        return {rows:unique, scripts, audio, html:document.documentElement.outerHTML};
    }""")


def _click_track(page, label: str) -> bool:
    try:
        return bool(page.evaluate(
            r"""label => {
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
            }""",
            label,
        ))
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
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36",
            locale="ru-RU",
        )
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
                seen_resolvers.add(url)
                resolver_urls.append(url)

        def remember_media(url: str) -> None:
            if url not in seen_media:
                seen_media.add(url)
                media.append(url)

        def on_request(request) -> None:
            try:
                u = str(request.url)
                if "poleknig.com" in (urlsplit(u).hostname or "") and urlsplit(u).path.startswith("/files/"):
                    remember_resolver(u)
            except Exception:
                pass

        def on_response(response) -> None:
            nonlocal redirects
            try:
                response_url = str(response.url)
                status = int(response.status)
                headers = dict(response.headers or {})
                path = urlsplit(response_url).path
                if "poleknig.com" in (urlsplit(response_url).hostname or "") and path.startswith("/files/"):
                    remember_resolver(response_url)
                    target = redirect_target(response_url, status, headers)
                    diagnostics.append(f"{path}:{status}:location={'yes' if headers.get('location') else 'no'}")
                    if target:
                        redirects += 1
                        remember_media(target)
                elif _is_audio_url(response_url) and "poleknig.com/storage/" in response_url:
                    remember_media(response_url)
            except Exception as exc:
                diagnostics.append(f"response-error:{type(exc).__name__}")

        page.on("request", on_request)
        page.on("response", on_response)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(1000)

        # Inspect the actual player state before trying playback. This catches
        # resolver URLs hidden in data attributes/inline JS even when headless
        # Chromium refuses to initiate media playback.
        try:
            snapshot = _player_snapshot(page)
            embedded_text = "\n".join([snapshot.get("html", ""), *snapshot.get("scripts", [])])
            embedded_resolvers, embedded_media = extract_embedded_urls(embedded_text, page_url)
            for u in embedded_resolvers:
                remember_resolver(u)
            for u in embedded_media:
                remember_media(u)
            diagnostics.append(f"state-rows={len(snapshot.get('rows', []))}")
            diagnostics.append(f"state-scripts={len(snapshot.get('scripts', []))}")
            diagnostics.append(f"state-audio={len(snapshot.get('audio', []))}")
            diagnostics.append(f"state-resolvers={len(embedded_resolvers)}")
            diagnostics.append(f"state-media={len(embedded_media)}")
            for row in snapshot.get("rows", [])[:8]:
                attrs = row.get("attrs", {})
                safe_attrs = {k:v for k,v in attrs.items() if k.lower() not in {"cookie", "authorization"}}
                diagnostics.append(f"row={row.get('text')}:{row.get('tag')}:{str(safe_attrs)[:500]}")
        except Exception as exc:
            diagnostics.append(f"state-error:{type(exc).__name__}")

        misses = 0
        for number in range(1, max_tracks + 1):
            label = f"{number:02d}"
            before_resolvers = len(resolver_urls)
            before_media = len(media)
            if not _click_track(page, label):
                misses += 1
                if misses >= 3:
                    break
                continue
            clicks += 1
            misses = 0
            waited = 0
            while waited < 700 and len(media) == before_media and len(resolver_urls) == before_resolvers:
                page.wait_for_timeout(100)
                waited += 100

        diagnostics.insert(0, f"clicks={clicks}")
        diagnostics.insert(1, f"resolvers={len(resolver_urls)}")
        diagnostics.insert(2, f"redirects={redirects}")
        context.close()
        browser.close()

    return BrowserResolveResult(clicks=clicks, redirects=redirects, media=media, resolver_urls=resolver_urls, diagnostics=diagnostics)
