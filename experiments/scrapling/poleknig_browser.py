from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import sync_playwright

AUDIO_EXTENSIONS = (".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac")


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
    """Return a reusable audio target from a Poleknig /files redirect."""
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


def _click_track(page, label: str) -> bool:
    """Click the smallest DOM node whose visible label is exactly 01, 02, ..."""
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

        # Prevent the large 206 audio body from being downloaded. The /files/
        # 302 is still allowed through and is enough to recover the final MP3.
        def route_handler(route) -> None:
            request_url = route.request.url
            if _is_audio_url(request_url) and "poleknig.com/storage/" in request_url:
                route.abort()
            else:
                route.continue_()

        page.route("**/*", route_handler)

        def on_response(response) -> None:
            nonlocal redirects
            try:
                response_url = str(response.url)
                status = int(response.status)
                headers = dict(response.headers or {})
                path = urlsplit(response_url).path
                if "poleknig.com" in (urlsplit(response_url).hostname or "") and path.startswith("/files/"):
                    if response_url not in seen_resolvers:
                        seen_resolvers.add(response_url)
                        resolver_urls.append(response_url)
                    target = redirect_target(response_url, status, headers)
                    diagnostics.append(f"{path}:{status}:location={'yes' if headers.get('location') else 'no'}")
                    if target:
                        redirects += 1
                        if target not in seen_media:
                            seen_media.add(target)
                            media.append(target)
                elif _is_audio_url(response_url) and "poleknig.com/storage/" in response_url:
                    if response_url not in seen_media:
                        seen_media.add(response_url)
                        media.append(response_url)
            except Exception as exc:
                diagnostics.append(f"response-error:{type(exc).__name__}")

        page.on("response", on_response)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(600)

        misses = 0
        for number in range(1, max_tracks + 1):
            label = f"{number:02d}"
            before = len(media)
            if not _click_track(page, label):
                misses += 1
                if misses >= 3:
                    break
                continue
            clicks += 1
            misses = 0

            # The redirect appears almost immediately after the player switches
            # tracks. Stop waiting as soon as a new target is captured.
            waited = 0
            while waited < 1200 and len(media) == before:
                page.wait_for_timeout(100)
                waited += 100

        diagnostics.insert(0, f"clicks={clicks}")
        diagnostics.insert(1, f"resolvers={len(resolver_urls)}")
        diagnostics.insert(2, f"redirects={redirects}")
        context.close()
        browser.close()

    return BrowserResolveResult(
        clicks=clicks,
        redirects=redirects,
        media=media,
        resolver_urls=resolver_urls,
        diagnostics=diagnostics,
    )
