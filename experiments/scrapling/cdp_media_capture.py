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


def _mark_track_target(page, site: str, index: int) -> dict | None:
    """Find a player row and mark it, but do not synthesize a click in JS.

    The actual input is sent later through Playwright's mouse so the page sees
    normal browser-generated pointer/mouse events instead of element.click().
    """
    try:
        return page.evaluate(
            r"""({site,index}) => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                const wanted = site === 'poleknig'
                    ? new RegExp('^' + String(index + 1).padStart(2, '0') + '$')
                    : site === 'izib'
                        ? new RegExp('^' + String(index + 1).padStart(2, '0') + '(?:\\s+\\d{1,2}:\\d{2})?$')
                        : new RegExp('(?:^|\\s)Игра Кота\\. Книга вторая_' + index + '(?:\\s|$)');

                const all = Array.from(document.querySelectorAll('body *'));
                let candidates = all.filter(el => wanted.test(norm(el.innerText || el.textContent)));
                // Prefer leaves so a whole player/container is never selected.
                const leaves = candidates.filter(el => !Array.from(el.children).some(c => wanted.test(norm(c.innerText || c.textContent))));
                if (leaves.length) candidates = leaves;
                candidates = candidates.filter(el => {
                    const r = el.getBoundingClientRect();
                    const st = getComputedStyle(el);
                    return r.width > 2 && r.height > 2 && st.visibility !== 'hidden' && st.display !== 'none';
                });
                if (!candidates.length) return null;
                candidates.sort((a,b) => {
                    const ar=a.getBoundingClientRect(), br=b.getBoundingClientRect();
                    return ar.width*ar.height - br.width*br.height;
                });
                const leaf = candidates[0];
                // Clicking the leaf is intentional: browser mouse events bubble to
                // delegated handlers on the row/playlist, unlike JS element.click().
                document.querySelectorAll('[data-oai-cdp-target]').forEach(el => el.removeAttribute('data-oai-cdp-target'));
                leaf.setAttribute('data-oai-cdp-target', '1');
                leaf.scrollIntoView({block:'center', inline:'nearest'});
                const r=leaf.getBoundingClientRect();
                const parent=leaf.parentElement;
                return {
                    text:norm(leaf.innerText || leaf.textContent).slice(0,180),
                    tag:leaf.tagName,
                    cls:String(leaf.className || '').slice(0,180),
                    parentTag:parent ? parent.tagName : '',
                    parentCls:parent ? String(parent.className || '').slice(0,180) : '',
                    x:r.x,y:r.y,width:r.width,height:r.height,
                    html:leaf.outerHTML.slice(0,500)
                };
            }""",
            {"site": site, "index": index},
        )
    except Exception:
        return None


def _trusted_click(page, site: str, index: int) -> tuple[bool, str]:
    target = _mark_track_target(page, site, index)
    if not target:
        return False, "not-found"
    try:
        # Re-read the box after scroll/layout settles, then use Playwright mouse.
        locator = page.locator('[data-oai-cdp-target="1"]').first
        box = locator.bounding_box()
        if not box:
            return False, f"no-box:{target.get('text','')[:120]}"
        x = box["x"] + box["width"] / 2
        y = box["y"] + box["height"] / 2
        page.mouse.move(x, y)
        page.mouse.down()
        page.wait_for_timeout(35)
        page.mouse.up()
        info = f"{target.get('text','')}:{target.get('tag','')}:{target.get('parentTag','')}:{target.get('cls','')[:100]}"
        return True, info
    except Exception as exc:
        return False, f"click-error:{type(exc).__name__}"


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
            if url not in seen_resolvers:
                seen_resolvers.add(url)
                resolvers.append(url)
        if _audio(url) or resource_type.lower() == "media":
            if url.startswith("http") and url not in seen_media:
                seen_media.add(url)
                media.append(url)
                diagnostics.append(f"media-{source}:{resource_type}:{url[:900]}")

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(
            user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36",
            locale="ru-RU",
        )
        page = context.new_page()
        session = context.new_cdp_session(page)
        session.send("Network.enable", {"maxTotalBufferSize": 1000000, "maxResourceBufferSize": 100000})

        def request_event(params) -> None:
            nonlocal request_count
            request_count += 1
            req = params.get("request", {})
            url = str(req.get("url", ""))
            typ = str(params.get("type", ""))
            remember(url, typ, "request")
            redirect = params.get("redirectResponse") or {}
            if redirect:
                old_url = str(redirect.get("url", ""))
                headers = redirect.get("headers") or {}
                location = headers.get("location") or headers.get("Location")
                if old_url and location and (site == "poleknig" and "/files/" in old_url or _audio(str(location))):
                    diagnostics.append(f"redirect:{redirect.get('status')}:{old_url[:500]} -> {str(location)[:700]}")
                    remember(str(location), "Media" if _audio(str(location)) else "", "redirect")

        def response_event(params) -> None:
            response = params.get("response", {})
            url = str(response.get("url", ""))
            typ = str(params.get("type", ""))
            remember(url, typ, "response")
            if site == "poleknig" and urlsplit(url).path.startswith("/files/"):
                headers = response.get("headers") or {}
                location = headers.get("location") or headers.get("Location")
                diagnostics.append(f"resolver-response:{response.get('status')}:location={'yes' if location else 'no'}:{url[:700]}")
                if location:
                    remember(str(location), "Media" if _audio(str(location)) else "", "location")

        session.on("Network.requestWillBeSent", request_event)
        session.on("Network.responseReceived", response_event)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(1200)

        clicks = 0
        misses = 0
        for index in range(max_tracks):
            ok, info = _trusted_click(page, site, index)
            if not ok:
                misses += 1
                if index < 4 or misses <= 2:
                    diagnostics.append(f"target-{index}:miss:{info}")
                if misses >= 4:
                    break
                continue
            clicks += 1
            misses = 0
            if index < 8:
                diagnostics.append(f"target-{index}:hit:{info}")
            page.wait_for_timeout(500)

        diagnostics.insert(0, f"cdp-clicks={clicks}")
        diagnostics.insert(1, f"cdp-requests={request_count}")
        diagnostics.insert(2, f"cdp-resolvers={len(resolvers)}")
        diagnostics.insert(3, f"cdp-media={len(media)}")
        context.close()
        browser.close()
    return CdpCaptureResult(requests=request_count, media=media, resolvers=resolvers, diagnostics=diagnostics)
