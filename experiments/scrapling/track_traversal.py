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


def _wait_new_media(page, media_responses: set[str], before: set[str], timeout_ms: int = 900) -> bool:
    elapsed = 0
    while elapsed < timeout_ms:
        if media_responses - before:
            return True
        page.wait_for_timeout(100)
        elapsed += 100
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
            text = re.sub(r"\s+", " ", (item.inner_text(timeout=400) or "")).strip()
            if text and text in seen_text:
                continue
            if text:
                seen_text.add(text)
            before = set(media_responses)
            item.scroll_into_view_if_needed(timeout=500)
            item.click(timeout=800, force=True)
            _wait_new_media(page, media_responses, before)
            clicked += 1
            if clicked >= limit:
                break
        except Exception:
            continue
    return clicked


def _text_row_candidates(page, host: str, limit: int) -> list[dict]:
    """Find smallest visible nodes matching the chapter labels seen in real browsers."""
    try:
        rows = page.evaluate("""({host, limit}) => {
            const norm = s => (s || '').replace(/\s+/g, ' ').trim();
            const visible = el => {
                const r = el.getBoundingClientRect();
                const s = getComputedStyle(el);
                return r.width > 2 && r.height > 2 && s.visibility !== 'hidden' && s.display !== 'none';
            };
            const matches = text => {
                if (host.includes('knigavuhe.org')) return /_\d+(?:\s+\d+:\d+)?$/.test(text);
                if (host.includes('izib.')) return /\b\d{2}(?:\s+\d+:\d+)?$/.test(text);
                if (host.includes('poleknig.com')) return /^\d{1,3}$/.test(text);
                return false;
            };
            const out = [];
            for (const el of document.querySelectorAll('body *')) {
                if (!visible(el)) continue;
                const text = norm(el.innerText || el.textContent);
                if (!matches(text)) continue;
                const childMatch = Array.from(el.children).some(c => matches(norm(c.innerText || c.textContent)));
                if (childMatch) continue;
                out.push({text, tag: el.tagName, cls: String(el.className || '')});
                if (out.length >= limit) break;
            }
            return out;
        }""", {"host": host, "limit": limit}) or []
        return [r for r in rows if isinstance(r, dict)]
    except Exception:
        return []


def _click_text_rows(page, host: str, media_responses: set[str], limit: int) -> int:
    rows = _text_row_candidates(page, host, limit)
    clicked = 0
    for row in rows:
        text = str(row.get("text") or "").strip()
        if not text:
            continue
        try:
            before = set(media_responses)
            did_click = page.evaluate("""text => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                const all = Array.from(document.querySelectorAll('body *'));
                let el = all.find(n => norm(n.innerText || n.textContent) === text &&
                    !Array.from(n.children).some(c => norm(c.innerText || c.textContent) === text));
                if (!el) return false;
                const clickable = el.closest('button,a,[role=button],[onclick],[data-track],[data-audio],[data-file]') || el;
                clickable.scrollIntoView({block:'center'});
                clickable.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true, view:window}));
                clickable.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true, view:window}));
                clickable.click();
                return true;
            }""", text)
            if did_click:
                _wait_new_media(page, media_responses, before, timeout_ms=700)
                clicked += 1
        except Exception:
            continue
    return clicked


def _site_playlist_rows(page, url: str, media_responses: set[str], limit: int = 50) -> int:
    host = urlsplit(url).hostname or ""

    # First use visible row text. This does not depend on unstable CSS class names.
    if any(key in host for key in ("knigavuhe.org", "izib.", "poleknig.com")):
        clicked = _click_text_rows(page, host, media_responses, limit)
        if clicked:
            return clicked

    if "knigavuhe.org" in host:
        for selector in ("[class*='book'] [class*='track']", "[class*='playlist'] > *", "[class*='book'] li", "[class*='player'] [class*='item']"):
            locator = page.locator(selector).filter(has_text=re.compile(r"_\d+\s*(?:\d+:\d+)?$"))
            clicked = _click_locator_rows(page, locator, media_responses, limit)
            if clicked:
                return clicked
        return 0

    if "izib." in host:
        rx = re.compile(r"\b\d{2}\s*(?:\d+:\d+)?$")
        for selector in ("[class*='playlist'] > *", "[class*='player'] [class*='track']", "[class*='player'] li", "[class*='audio'] li"):
            clicked = _click_locator_rows(page, page.locator(selector).filter(has_text=rx), media_responses, limit)
            if clicked:
                return clicked
        return 0

    if "poleknig.com" in host:
        rx = re.compile(r"^\s*\d{1,3}\s*$")
        for selector in ("[class*='player'] [class*='playlist'] > *", "[class*='player'] [class*='track']", "[class*='player'] li", "[class*='playlist'] > *"):
            clicked = _click_locator_rows(page, page.locator(selector).filter(has_text=rx), media_responses, limit)
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
                text = (item.inner_text(timeout=400) or "").strip()
                if text and text in seen_text:
                    continue
                if text:
                    seen_text.add(text)
                before = set(media_responses)
                item.click(timeout=700, force=True)
                _wait_new_media(page, media_responses, before, timeout_ms=600)
                clicked += 1
                if clicked >= limit:
                    return clicked
            except Exception:
                continue
    return clicked


def _browser_media_urls(page) -> set[str]:
    try:
        rows = page.evaluate("""() => {
            const out = new Set();
            const add = value => {
                if (!value) return;
                try { out.add(new URL(value, location.href).href); } catch (_) {}
            };
            const attrs = ['src','href','data-src','data-url','data-file','data-audio'];
            for (const node of document.querySelectorAll('audio,source,a,[data-src],[data-url],[data-file],[data-audio]')) {
                for (const attr of attrs) add(node.getAttribute && node.getAttribute(attr));
                add(node.currentSrc);
            }
            for (const entry of performance.getEntriesByType('resource')) add(entry.name);
            const html = document.documentElement.outerHTML.replace(/\\\//g, '/');
            const rx = /https?:\\/\\/[^\"'<>\\s]+?(?:mp3|m4a|m4b|aac|ogg|opus|flac|m3u8)(?:\\?[^\"'<>\\s]*)?/ig;
            for (const match of html.matchAll(rx)) add(match[0]);
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
                response = page.request.head(candidate, headers={"Referer": page.url}, timeout=1400, fail_on_status_code=False)
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
    browser_urls: set[str] = set()
    expanded_urls: set[str] = set()

    def setup(page) -> None:
        def on_response(response) -> None:
            try:
                headers = dict(response.headers or {})
                network.append({"url": str(response.url), "status": int(response.status), "headers": headers, "body": ""})
                content_type = (headers.get("content-type") or "").lower()
                if response.request.resource_type == "media" or content_type.startswith("audio/"):
                    media_responses.add(str(response.url))
            except Exception:
                pass
        page.on("response", on_response)

    def action(page) -> None:
        host = urlsplit(url).hostname or ""
        _click(page, PLAY_SELECTORS)
        page.wait_for_timeout(350)
        browser_urls.update(_browser_media_urls(page))

        # Poleknig has many tracks and each click can wait on a streamed response; six rows
        # are enough to prove extraction without consuming the entire per-site timeout.
        site_limit = min(max_steps, 8 if "poleknig.com" in host else max_steps)
        clicked = _site_playlist_rows(page, url, media_responses, limit=site_limit)
        if not clicked:
            _click_playlist_rows(page, media_responses, limit=site_limit)
        browser_urls.update(_browser_media_urls(page))

        if len(media_responses) <= 1:
            for _ in range(min(max_steps, 8)):
                before = set(media_responses)
                if not _click(page, NEXT_SELECTORS):
                    break
                _wait_new_media(page, media_responses, before, timeout_ms=550)
                browser_urls.update(_browser_media_urls(page))

        seeds = {u for u in (set(media_responses) | set(browser_urls)) if u.startswith(("http://", "https://"))}
        if "poleknig.com" not in host:
            expanded_urls.update(_expand_numbered(page, seeds))

    page = DynamicFetcher.fetch(
        url, headless=True, capture_xhr=capture_xhr, page_setup=setup, page_action=action,
        wait=350, timeout=50000, network_idle=False,
    )

    found = {c.url for c in collect_network_responses(network)}
    found.update(media_responses)
    found.update(browser_urls)
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
