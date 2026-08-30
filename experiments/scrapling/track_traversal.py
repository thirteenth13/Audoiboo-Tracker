from __future__ import annotations

import re
from urllib.parse import urlsplit, urlunsplit

from scrapling.fetchers import DynamicFetcher

from media_detector import collect_network_responses


NEXT_SELECTORS = (
    "button[aria-label*='next' i]",
    "[role='button'][aria-label*='next' i]",
    "button[title*='next' i]",
    "[aria-label*='след' i]",
    "[title*='след' i]",
    ".player-next",
    ".next-track",
    ".jp-next",
    ".skip-next",
    "[class*='skip-next']",
    "[class*='step-forward']",
    "[class*='angle-right']",
    "[class*='chevron-right']",
    "[data-action*='next' i]",
    "[onclick*='next' i]",
)

PLAY_SELECTORS = (
    "button[aria-label*='play' i]",
    "[role='button'][aria-label*='play' i]",
    "button[title*='play' i]",
    ".player-play",
    ".play-button",
    ".jp-play",
    "button[class*='play']",
)

NUMBERED_TRACK_RE = re.compile(r"^(?P<prefix>.*?/)(?P<number>\d+)(?P<ext>\.(?:mp3|m4a|m4b|aac|ogg|opus|flac))$", re.I)


def numbered_track_parts(url: str) -> tuple[str, int, str] | None:
    parts = urlsplit(url)
    match = NUMBERED_TRACK_RE.match(parts.path)
    if not match:
        return None
    prefix = urlunsplit((parts.scheme, parts.netloc, match.group("prefix"), "", ""))
    return prefix, int(match.group("number")), match.group("ext")


def numbered_track_url(seed: str, number: int) -> str | None:
    parsed = numbered_track_parts(seed)
    if not parsed:
        return None
    prefix, _, ext = parsed
    return f"{prefix}{number}{ext}"


def _click(page, selectors: tuple[str, ...]) -> bool:
    for selector in selectors:
        try:
            item = page.locator(selector)
            if item.count() and item.first.is_visible():
                item.first.click(timeout=1000, force=True)
                return True
        except Exception:
            continue
    return False


def _dom_media_urls(page) -> set[str]:
    try:
        rows = page.evaluate(
            """() => {
                const out = new Set();
                const attrs = ['src', 'href', 'data-src', 'data-url', 'data-file', 'data-audio'];
                for (const node of document.querySelectorAll('audio,source,a,[data-src],[data-url],[data-file],[data-audio]')) {
                    for (const attr of attrs) {
                        const value = node.getAttribute && node.getAttribute(attr);
                        if (value) {
                            try { out.add(new URL(value, location.href).href); } catch (_) {}
                        }
                    }
                    if (node.currentSrc) out.add(node.currentSrc);
                }
                return Array.from(out);
            }"""
        ) or []
        return {str(url) for url in rows if isinstance(url, str)}
    except Exception:
        return set()


def _expand_numbered(page, seeds: set[str], max_tracks: int = 80) -> set[str]:
    found: set[str] = set()
    for seed in sorted(seeds):
        parsed = numbered_track_parts(seed)
        if not parsed:
            continue
        _, start, _ = parsed
        misses = 0
        for number in range(start + 1, start + 1 + max_tracks):
            candidate = numbered_track_url(seed, number)
            if not candidate:
                break
            try:
                response = page.request.head(
                    candidate,
                    headers={"Referer": page.url},
                    timeout=1800,
                    fail_on_status_code=False,
                )
                content_type = (response.headers.get("content-type") or "").lower()
                if 200 <= response.status < 400 and (content_type.startswith("audio/") or candidate.lower().endswith((".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac"))):
                    found.add(candidate)
                    misses = 0
                else:
                    misses += 1
            except Exception:
                misses += 1
            if misses >= 2:
                break
    return found


def traverse(url: str, capture_xhr: str, max_steps: int = 40) -> tuple[int, list[str]]:
    network: list[dict] = []
    media_responses: set[str] = set()
    dom_urls: set[str] = set()
    expanded_urls: set[str] = set()

    def setup(page) -> None:
        def on_response(response) -> None:
            try:
                resource_type = response.request.resource_type
                headers = dict(response.headers or {})
                row = {
                    "url": str(response.url),
                    "status": int(response.status),
                    "headers": headers,
                    "body": "",
                }
                network.append(row)
                content_type = (headers.get("content-type") or "").lower()
                if resource_type == "media" or content_type.startswith("audio/"):
                    media_responses.add(str(response.url))
            except Exception:
                pass
        page.on("response", on_response)

    def action(page) -> None:
        _click(page, PLAY_SELECTORS)
        page.wait_for_timeout(1200)
        dom_urls.update(_dom_media_urls(page))
        seen_states: set[str] = set()
        repeats = 0
        for _ in range(max_steps):
            try:
                state = page.evaluate("() => Array.from(document.querySelectorAll('audio,video')).map(x => x.currentSrc || x.src || '').join('|')") or ""
            except Exception:
                state = ""
            if state and state in seen_states:
                repeats += 1
            elif state:
                seen_states.add(state)
                repeats = 0
            dom_urls.update(_dom_media_urls(page))
            if repeats >= 2 or not _click(page, NEXT_SELECTORS):
                break
            page.wait_for_timeout(850)
        dom_urls.update(_dom_media_urls(page))
        seeds = set(media_responses) | set(dom_urls)
        expanded_urls.update(_expand_numbered(page, seeds))

    page = DynamicFetcher.fetch(
        url,
        headless=True,
        capture_xhr=capture_xhr,
        page_setup=setup,
        page_action=action,
        wait=700,
        timeout=65000,
        network_idle=False,
    )

    found = {c.url for c in collect_network_responses(network)}
    found.update(media_responses)
    found.update(url for url in dom_urls if url.lower().split("?", 1)[0].endswith((".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac", ".m3u8")))
    found.update(expanded_urls)

    for item in getattr(page, "captured_xhr", []) or []:
        try:
            rows = [{
                "url": str(item.url),
                "status": int(item.status),
                "headers": dict(item.headers or {}),
                "body": item.body if isinstance(item.body, str) else "",
            }]
            found.update(c.url for c in collect_network_responses(rows))
        except Exception:
            pass
    return len(network), sorted(found)
