from __future__ import annotations

import re
from urllib.parse import urlsplit, urlunsplit

from scrapling.fetchers import DynamicFetcher

from media_detector import collect_network_responses

AUDIO_EXTENSIONS = (".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac")

NEXT_SELECTORS = (
    "button[aria-label*='next' i]", "[role='button'][aria-label*='next' i]", "button[title*='next' i]",
    "[aria-label*='след' i]", "[title*='след' i]", ".player-next", ".next-track", ".jp-next",
    ".skip-next", "[class*='skip-next']", "[class*='step-forward']", "[class*='angle-right']",
    "[class*='chevron-right']", "[data-action*='next' i]", "[onclick*='next' i]",
)

PLAY_SELECTORS = (
    "button[aria-label*='play' i]", "[role='button'][aria-label*='play' i]", "button[title*='play' i]",
    ".player-play", ".play-button", ".jp-play", "button[class*='play']",
)

PLAYLIST_SELECTORS = (
    "[class*='playlist'] li", "[class*='playlist'] [class*='track']", "[class*='tracks'] li",
    "[class*='tracks'] [class*='track']", "[class*='player'] li", "audio ~ * li",
    "[data-track]", "[data-audio]", "[data-file]",
)

NUMBERED_TRACK_RE = re.compile(r"^(?P<prefix>.*?)(?P<number>\d+)(?P<ext>\.(?:mp3|m4a|m4b|aac|ogg|opus|flac))$", re.I)


def numbered_track_parts(url: str) -> tuple[str, int, str, str] | None:
    parts = urlsplit(url)
    match = NUMBERED_TRACK_RE.match(parts.path)
    if not match:
        return None
    prefix = urlunsplit((parts.scheme, parts.netloc, match.group("prefix"), "", ""))
    return prefix, int(match.group("number")), match.group("ext"), parts.query


def numbered_track_url(seed: str, number: int) -> str | None:
    parsed = numbered_track_parts(seed)
    if not parsed:
        return None
    prefix, _, ext, query = parsed
    result = f"{prefix}{number}{ext}"
    return f"{result}?{query}" if query else result


def _click(page, selectors: tuple[str, ...]) -> bool:
    for selector in selectors:
        try:
            item = page.locator(selector)
            if item.count() and item.first.is_visible():
                item.first.click(timeout=900, force=True)
                return True
        except Exception:
            continue
    return False


def _click_playlist_rows(page, limit: int = 36) -> int:
    clicked = 0
    seen_text: set[str] = set()
    for selector in PLAYLIST_SELECTORS:
        try:
            locator = page.locator(selector)
            count = min(locator.count(), limit)
        except Exception:
            continue
        for i in range(count):
            try:
                item = locator.nth(i)
                if not item.is_visible():
                    continue
                text = (item.inner_text(timeout=500) or "").strip()
                if text and text in seen_text:
                    continue
                if text:
                    seen_text.add(text)
                item.click(timeout=900, force=True)
                page.wait_for_timeout(260)
                clicked += 1
                if clicked >= limit:
                    return clicked
            except Exception:
                continue
    return clicked


def _dom_media_urls(page) -> set[str]:
    try:
        rows = page.evaluate("""() => {
            const out = new Set();
            const attrs = ['src','href','data-src','data-url','data-file','data-audio'];
            for (const node of document.querySelectorAll('audio,source,a,[data-src],[data-url],[data-file],[data-audio]')) {
                for (const attr of attrs) {
                    const value = node.getAttribute && node.getAttribute(attr);
                    if (value) { try { out.add(new URL(value, location.href).href); } catch (_) {} }
                }
                if (node.currentSrc) out.add(node.currentSrc);
            }
            return Array.from(out);
        }""") or []
        return {str(url) for url in rows if isinstance(url, str)}
    except Exception:
        return set()


def _expand_numbered(page, seeds: set[str], max_tracks: int = 80) -> set[str]:
    found: set[str] = set()
    for seed in sorted(seeds):
        parsed = numbered_track_parts(seed)
        if not parsed:
            continue
        _, start, _, _ = parsed
        misses = 0
        for number in range(start + 1, start + 1 + max_tracks):
            candidate = numbered_track_url(seed, number)
            if not candidate:
                break
            try:
                response = page.request.head(candidate, headers={"Referer": page.url}, timeout=1600, fail_on_status_code=False)
                content_type = (response.headers.get("content-type") or "").lower()
                if 200 <= response.status < 400 and (content_type.startswith("audio/") or urlsplit(candidate).path.lower().endswith(AUDIO_EXTENSIONS)):
                    found.add(candidate)
                    misses = 0
                else:
                    misses += 1
            except Exception:
                misses += 1
            if misses >= 2:
                break
    return found


def traverse(url: str, capture_xhr: str, max_steps: int = 28) -> tuple[int, list[str]]:
    network: list[dict] = []
    media_responses: set[str] = set()
    dom_urls: set[str] = set()
    expanded_urls: set[str] = set()

    def setup(page) -> None:
        def on_response(response) -> None:
            try:
                headers = dict(response.headers or {})
                row = {"url": str(response.url), "status": int(response.status), "headers": headers, "body": ""}
                network.append(row)
                content_type = (headers.get("content-type") or "").lower()
                if response.request.resource_type == "media" or content_type.startswith("audio/"):
                    media_responses.add(str(response.url))
            except Exception:
                pass
        page.on("response", on_response)

    def action(page) -> None:
        _click(page, PLAY_SELECTORS)
        page.wait_for_timeout(700)
        dom_urls.update(_dom_media_urls(page))

        # Some players expose each chapter as a clickable playlist row rather than a Next button.
        _click_playlist_rows(page, limit=max_steps)
        dom_urls.update(_dom_media_urls(page))

        for _ in range(max_steps):
            if not _click(page, NEXT_SELECTORS):
                break
            page.wait_for_timeout(320)
            dom_urls.update(_dom_media_urls(page))

        seeds = {u for u in (set(media_responses) | set(dom_urls)) if u.startswith(("http://", "https://"))}
        expanded_urls.update(_expand_numbered(page, seeds))

    page = DynamicFetcher.fetch(
        url, headless=True, capture_xhr=capture_xhr, page_setup=setup, page_action=action,
        wait=500, timeout=50000, network_idle=False,
    )

    found = {c.url for c in collect_network_responses(network)}
    found.update(media_responses)
    found.update(u for u in dom_urls if u.startswith(("http://", "https://")) and urlsplit(u).path.lower().endswith(AUDIO_EXTENSIONS + (".m3u8",)))
    found.update(expanded_urls)

    for item in getattr(page, "captured_xhr", []) or []:
        try:
            rows = [{"url": str(item.url), "status": int(item.status), "headers": dict(item.headers or {}), "body": item.body if isinstance(item.body, str) else ""}]
            found.update(c.url for c in collect_network_responses(rows))
        except Exception:
            pass

    found = {u for u in found if not u.lower().startswith("blob:")}
    return len(network), sorted(found)
