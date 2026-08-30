from __future__ import annotations

from scrapling.fetchers import DynamicFetcher

from media_detector import collect_network_responses


NEXT_SELECTORS = (
    "button[aria-label*='next' i]",
    "[role='button'][aria-label*='next' i]",
    "button[title*='next' i]",
    ".player-next",
    ".next-track",
    ".jp-next",
    "button[class*='next']",
    "[class*='player'] [class*='next']",
    "[data-action*='next' i]",
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


def traverse(url: str, capture_xhr: str, max_steps: int = 40) -> tuple[int, list[str]]:
    network: list[dict] = []

    def setup(page) -> None:
        def on_response(response) -> None:
            try:
                network.append({
                    "url": str(response.url),
                    "status": int(response.status),
                    "headers": dict(response.headers or {}),
                    "body": "",
                })
            except Exception:
                pass
        page.on("response", on_response)

    def action(page) -> None:
        _click(page, PLAY_SELECTORS)
        page.wait_for_timeout(1200)
        seen: set[str] = set()
        repeats = 0
        for _ in range(max_steps):
            try:
                state = page.evaluate("() => Array.from(document.querySelectorAll('audio,video')).map(x => x.currentSrc || x.src || '').join('|')") or ""
            except Exception:
                state = ""
            if state and state in seen:
                repeats += 1
            elif state:
                seen.add(state)
                repeats = 0
            if repeats >= 2 or not _click(page, NEXT_SELECTORS):
                break
            page.wait_for_timeout(900)

    page = DynamicFetcher.fetch(
        url,
        headless=True,
        capture_xhr=capture_xhr,
        page_setup=setup,
        page_action=action,
        wait=700,
        timeout=55000,
        network_idle=False,
    )
    found = {c.url for c in collect_network_responses(network)}
    for item in getattr(page, "captured_xhr", []) or []:
        try:
            rows = [{"url": str(item.url), "status": int(item.status), "headers": dict(item.headers or {}), "body": item.body if isinstance(item.body, str) else ""}]
            found.update(c.url for c in collect_network_responses(rows))
        except Exception:
            pass
    return len(network), sorted(found)
