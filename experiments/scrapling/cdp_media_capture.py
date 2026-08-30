from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright

AUDIO_RE = re.compile(r"\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:$|\?)", re.I)


@dataclass
class CdpCaptureResult:
    requests: int
    media: list[str]
    resolvers: list[str]
    diagnostics: list[str]


def _audio(url: str) -> bool:
    return bool(AUDIO_RE.search(urlsplit(url).path))


def _click_label(page, label: str) -> bool:
    try:
        return bool(page.evaluate(r"""label => {
            const norm = s => (s || '').replace(/\s+/g,' ').trim();
            const xs = Array.from(document.querySelectorAll('body *')).filter(el =>
                norm(el.innerText || el.textContent) === label &&
                !Array.from(el.children).some(c => norm(c.innerText || c.textContent) === label));
            if (!xs.length) return false;
            xs.sort((a,b) => { const x=a.getBoundingClientRect(), y=b.getBoundingClientRect(); return x.width*x.height-y.width*y.height; });
            const el = xs[0].closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]') || xs[0];
            el.scrollIntoView({block:'center'}); el.click(); return true;
        }""", label))
    except Exception:
        return False


def capture(page_url: str, site: str, max_tracks: int = 30, timeout_ms: int = 30000) -> CdpCaptureResult:
    media: list[str] = []
    resolvers: list[str] = []
    seen_media: set[str] = set()
    seen_resolvers: set[str] = set()
    diagnostics: list[str] = []
    request_count = 0

    def remember(url: str, resource_type: str = "", source: str = "") -> None:
        path = urlsplit(url).path
        host = urlsplit(url).hostname or ""
        if site == "poleknig" and host.endswith("poleknig.com") and path.startswith("/files/"):
            if url not in seen_resolvers: seen_resolvers.add(url); resolvers.append(url)
        if _audio(url) or resource_type.lower() == "media":
            if url.startswith("http") and url not in seen_media:
                seen_media.add(url); media.append(url); diagnostics.append(f"media-{source}:{resource_type}:{url[:900]}")

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36", locale="ru-RU")
        page = context.new_page()
        session = context.new_cdp_session(page)
        session.send("Network.enable", {"maxTotalBufferSize": 1000000, "maxResourceBufferSize": 100000})

        def request_event(params) -> None:
            nonlocal request_count
            request_count += 1
            req = params.get("request", {}); url = str(req.get("url", "")); typ = str(params.get("type", ""))
            remember(url, typ, "request")
            redirect = params.get("redirectResponse") or {}
            if redirect:
                old_url = str(redirect.get("url", "")); headers = redirect.get("headers") or {}; location = headers.get("location") or headers.get("Location")
                if old_url and location:
                    diagnostics.append(f"redirect:{redirect.get('status')}:{old_url[:500]} -> {str(location)[:700]}")
                    remember(str(location), "Media" if _audio(str(location)) else "", "redirect")

        def response_event(params) -> None:
            response = params.get("response", {}); url = str(response.get("url", "")); typ = str(params.get("type", ""))
            remember(url, typ, "response")
            if site == "poleknig" and urlsplit(url).path.startswith("/files/"):
                headers = response.get("headers") or {}; location = headers.get("location") or headers.get("Location")
                diagnostics.append(f"resolver-response:{response.get('status')}:location={'yes' if location else 'no'}:{url[:700]}")
                if location: remember(str(location), "Media" if _audio(str(location)) else "", "location")

        session.on("Network.requestWillBeSent", request_event)
        session.on("Network.responseReceived", response_event)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(1000)

        # Site-specific visible track labels seen in the real players.
        if site == "knigavuhe":
            labels = [f"Игра Кота. Книга вторая_{i}" for i in range(max_tracks)]
        else:
            labels = [f"{i:02d}" for i in range(1, max_tracks + 1)]
        clicks = 0; misses = 0
        for label in labels:
            if not _click_label(page, label):
                misses += 1
                if misses >= 4: break
                continue
            clicks += 1; misses = 0; page.wait_for_timeout(350)
        diagnostics.insert(0, f"cdp-clicks={clicks}")
        diagnostics.insert(1, f"cdp-requests={request_count}")
        diagnostics.insert(2, f"cdp-resolvers={len(resolvers)}")
        diagnostics.insert(3, f"cdp-media={len(media)}")
        context.close(); browser.close()
    return CdpCaptureResult(requests=request_count, media=media, resolvers=resolvers, diagnostics=diagnostics)
