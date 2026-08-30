from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import sync_playwright

AUDIO_EXTENSIONS = (".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac")


@dataclass
class CdpCaptureResult:
    clicks: int
    requests: int
    media: list[str]
    resolvers: list[str]
    redirects: list[str]
    diagnostics: list[str]


def _is_audio(url: str) -> bool:
    return urlsplit(url).path.lower().endswith(AUDIO_EXTENSIONS)


def _is_resolver(url: str) -> bool:
    parts = urlsplit(url)
    return (parts.hostname or "").endswith("poleknig.com") and parts.path.startswith("/files/")


def redirect_audio_target(request_url: str, redirect_response: dict | None) -> str | None:
    if not redirect_response or not _is_resolver(request_url):
        return None
    status = int(redirect_response.get("status") or 0)
    if status not in (301, 302, 303, 307, 308):
        return None
    headers = {str(k).lower(): str(v) for k, v in (redirect_response.get("headers") or {}).items()}
    location = headers.get("location")
    if not location:
        return None
    target = urljoin(request_url, location)
    return target if _is_audio(target) else None


def _click_labels(page, site: str, max_clicks: int) -> int:
    if site == "poleknig":
        pattern = r"^\d{2}$"
    elif site == "knigavuhe":
        pattern = r"_\d+\s*(?:\d+:\d+)?$"
    elif site == "izib":
        pattern = r"\b\d{2}\s*(?:\d+:\d+)?$"
    else:
        return 0

    return int(page.evaluate(
        r"""({pattern, maxClicks}) => {
            const rx = new RegExp(pattern);
            const norm = s => (s || '').replace(/\s+/g, ' ').trim();
            const nodes = Array.from(document.querySelectorAll('body *')).filter(el => {
                const text = norm(el.innerText || el.textContent);
                if (!rx.test(text)) return false;
                return !Array.from(el.children).some(c => rx.test(norm(c.innerText || c.textContent)));
            });
            const unique = [];
            const seen = new Set();
            for (const el of nodes) {
                const text = norm(el.innerText || el.textContent);
                if (seen.has(text)) continue;
                seen.add(text);
                unique.push(el);
            }
            let clicked = 0;
            for (const el of unique.slice(0, maxClicks)) {
                const target = el.closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]') || el;
                try {
                    target.scrollIntoView({block:'center'});
                    target.dispatchEvent(new MouseEvent('mousedown', {bubbles:true,cancelable:true,view:window}));
                    target.dispatchEvent(new MouseEvent('mouseup', {bubbles:true,cancelable:true,view:window}));
                    target.click();
                    clicked++;
                } catch (e) {}
            }
            return clicked;
        }""",
        {"pattern": pattern, "maxClicks": max_clicks},
    ))


def capture(site: str, page_url: str, max_clicks: int = 40, timeout_ms: int = 30000) -> CdpCaptureResult:
    media: list[str] = []
    resolvers: list[str] = []
    redirects: list[str] = []
    diagnostics: list[str] = []
    seen_media: set[str] = set()
    seen_resolvers: set[str] = set()
    seen_redirects: set[str] = set()
    request_count = 0
    clicks = 0

    def remember_media(url: str) -> None:
        if url and url not in seen_media:
            seen_media.add(url)
            media.append(url)

    def remember_resolver(url: str) -> None:
        if url and url not in seen_resolvers:
            seen_resolvers.add(url)
            resolvers.append(url)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36",
            locale="ru-RU",
        )
        page = context.new_page()
        session = context.new_cdp_session(page)
        session.send("Network.enable", {"maxTotalBufferSize": 1048576, "maxResourceBufferSize": 262144})

        def on_request(params: dict) -> None:
            nonlocal request_count
            request_count += 1
            request = params.get("request") or {}
            url = str(request.get("url") or "")
            resource_type = str(params.get("type") or "")
            redirect_response = params.get("redirectResponse")

            if _is_resolver(url):
                remember_resolver(url)

            if resource_type.lower() == "media" or _is_audio(url):
                remember_media(url)
                if len(diagnostics) < 30:
                    diagnostics.append(f"request:{resource_type or '-'}:{url}")

            if redirect_response:
                source_url = str(redirect_response.get("url") or url)
                target = redirect_audio_target(source_url, redirect_response)
                if target:
                    remember_media(target)
                    key = f"{source_url}->{target}"
                    if key not in seen_redirects:
                        seen_redirects.add(key)
                        redirects.append(key)
                    if len(diagnostics) < 30:
                        diagnostics.append(f"redirect:{int(redirect_response.get('status') or 0)}:{source_url}->{target}")

        def on_response(params: dict) -> None:
            response = params.get("response") or {}
            url = str(response.get("url") or "")
            resource_type = str(params.get("type") or "")
            mime = str(response.get("mimeType") or "")
            if _is_resolver(url):
                remember_resolver(url)
            if resource_type.lower() == "media" or mime.startswith("audio/") or _is_audio(url):
                remember_media(url)

        session.on("Network.requestWillBeSent", on_request)
        session.on("Network.responseReceived", on_response)

        # Once a direct media URL is visible to CDP we do not need the body.
        def route_handler(route) -> None:
            u = route.request.url
            if _is_audio(u) and not _is_resolver(u):
                route.abort()
            else:
                route.continue_()
        page.route("**/*", route_handler)

        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(1200)

        try:
            clicks = _click_labels(page, site, max_clicks)
        except Exception as exc:
            diagnostics.append(f"click-error:{type(exc).__name__}")

        # Process CDP events generated by the synchronous batch of clicks.
        page.wait_for_timeout(2500)
        diagnostics.insert(0, f"cdp-clicks={clicks}")
        diagnostics.insert(1, f"cdp-requests={request_count}")
        diagnostics.insert(2, f"cdp-resolvers={len(resolvers)}")
        diagnostics.insert(3, f"cdp-redirects={len(redirects)}")
        diagnostics.insert(4, f"cdp-media={len(media)}")

        session.detach()
        context.close()
        browser.close()

    return CdpCaptureResult(
        clicks=clicks,
        requests=request_count,
        media=media,
        resolvers=resolvers,
        redirects=redirects,
        diagnostics=diagnostics,
    )
