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
    width_match = re.search(r"(\d+)(?=\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)$)", urlsplit(seed).path, re.I)
    width = len(width_match.group(1)) if width_match else 1
    result = f"{prefix}{number:0{width}d}{ext}"
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


def _wait_new_media(page, media_responses: set[str], before: set[str], timeout_ms: int = 1600) -> bool:
    elapsed = 0
    while elapsed < timeout_ms:
        if media_responses - before:
            return True
        page.wait_for_timeout(120)
        elapsed += 120
    return False


def _click_locator_rows(page, locator, media_responses: set[str], limit: int) -> int:
    clicked = 0
    seen_text: set[str] = set()
    try:
        count = min(locator.count(), limit)
    except Exception:
        return 0
    for i in range(count):
        try:
            item = locator.nth(i)
            if not item.is_visible():
                continue
            text = re.sub(r"\s+", " ", (item.inner_text(timeout=500) or "")).strip()
            if text and text in seen_text:
                continue
            if text:
                seen_text.add(text)
            before = set(media_responses)
            item.scroll_into_view_if_needed(timeout=700)
            item.click(timeout=1000, force=True)
            _wait_new_media(page, media_responses, before)
            clicked += 1
            if clicked >= limit:
                break
        except Exception:
            continue
    return clicked


def _site_playlist_rows(page, url: str, media_responses: set[str], limit: int = 50) -> int:
    host = urlsplit(url).hostname or ""

    # Knigavuhe exposes chapter rows whose visible labels end in _0, _1, ...
    if "knigavuhe.org" in host:
        selectors = (
            "[class*='book'] [class*='track']",
            "[class*='playlist'] > *",
            "[class*='book'] li",
            "[class*='player'] [class*='item']",
        )
        for selector in selectors:
            locator = page.locator(selector).filter(has_text=re.compile(r"_\d+\s*(?:\d+:\d+)?$"))
            clicked = _click_locator_rows(page, locator, media_responses, limit)
            if clicked:
                return clicked
        try:
            locator = page.get_by_text(re.compile(r"_\d+$"))
            return _click_locator_rows(page, locator, media_responses, limit)
        except Exception:
            return 0

    # Izib chapters are numbered 01, 02, ... and live inside the player/playlist area.
    if "izib." in host:
        selectors = (
            "[class*='playlist'] > *",
            "[class*='player'] [class*='track']",
            "[class*='player'] li",
            "[class*='audio'] li",
        )
        rx = re.compile(r"\b\d{2}\s*(?:\d+:\d+)?$")
        for selector in selectors:
            locator = page.locator(selector).filter(has_text=rx)
            clicked = _click_locator_rows(page, locator, media_responses, limit)
            if clicked:
                return clicked
        return 0

    # Poleknig uses numeric chapter rows and hashed MP3 names, so actual clicks are required.
    if "poleknig.com" in host:
        selectors = (
            "[class*='player'] [class*='playlist'] > *",
            "[class*='player'] [class*='track']",
            "[class*='player'] li",
            "[class*='playlist'] > *",
        )
        rx = re.compile(r"^\s*\d{1,3}\s*$")
        for selector in selectors:
            locator = page.locator(selector).filter(has_text=rx)
            clicked = _click_locator_rows(page, locator, media_responses, limit)
            if clicked:
                return clicked
        return 0

    return 0


def _click_playlist_rows(page, media_responses: set[str], limit: int = 36) -> int:
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
                before = set(media_responses)
                item.click(timeout=900, force=True)
                _wait_new_media(page, media_responses, before, timeout_ms=800)
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


def _natural_media_key(url: str):
    path = urlsplit(url).path
    match = re.search(r"(\d+)(?=\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)$)", path, re.I)
    return (re.sub(r"\d+(?=\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)$)", "", path, flags=re.I), int(match.group(1)) if match else 10**9, url)


def traverse(url: str, capture_xhr: str, max_steps: int = 40) -> tuple[int, list[str]]:
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
        page.wait_for_timeout(500)
        dom_urls.update(_dom_media_urls(page))

        clicked = _site_playlist_rows(page, url, media_responses, limit=max_steps)
        if not clicked:
            _click_playlist_rows(page, media_responses, limit=max_steps)
        dom_urls.update(_dom_media_urls(page))

        # Keep Next as a final generic fallback only when playlist clicking produced little media.
        if len(media_responses) <= 1:
            for _ in range(max_steps):
                before = set(media_responses)
                if not _click(page, NEXT_SELECTORS):
                    break
                _wait_new_media(page, media_responses, before, timeout_ms=700)
                dom_urls.update(_dom_media_urls(page))

        seeds = {u for u in (set(media_responses) | set(dom_urls)) if u.startswith(("http://", "https://"))}
        host = urlsplit(url).hostname or ""
        if "poleknig.com" not in host:
            expanded_urls.update(_expand_numbered(page, seeds))

    page = DynamicFetcher.fetch(
        url, headless=True, capture_xhr=capture_xhr, page_setup=setup, page_action=action,
        wait=400, timeout=65000, network_idle=False,
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

    found = {
        u for u in found
        if not u.lower().startswith("blob:")
        and (urlsplit(u).path.lower().endswith(AUDIO_EXTENSIONS) or ".m3u8" in urlsplit(u).path.lower())
    }
    return len(network), sorted(found, key=_natural_media_key)
