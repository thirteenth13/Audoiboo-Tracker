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
    """Find a player row and mark it without synthesizing a JS click."""
    try:
        return page.evaluate(
            r"""({site,index}) => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                let wanted;
                if (site === 'poleknig') {
                    wanted = new RegExp('^' + String(index + 1).padStart(2, '0') + '$');
                } else if (site === 'izib') {
                    // Real IZIB rows look like:
                    // "Звёздная кровь 11. Колония Альфа 02" plus a duration
                    // in a sibling element. Match the trailing two-digit chapter
                    // number instead of assuming the entire row text is just "02".
                    wanted = new RegExp('(?:^|\\s)' + String(index + 1).padStart(2, '0') + '(?:\\s+\\d{1,2}:\\d{2})?$');
                } else {
                    wanted = new RegExp('(?:^|\\s)Игра Кота\\. Книга вторая_' + index + '(?:\\s|$)');
                }

                const all = Array.from(document.querySelectorAll('body *'));
                let candidates = all.filter(el => wanted.test(norm(el.innerText || el.textContent)));
                const leaves = candidates.filter(el => !Array.from(el.children).some(c => wanted.test(norm(c.innerText || c.textContent))));
                if (leaves.length) candidates = leaves;
                candidates = candidates.filter(el => {
                    const r = el.getBoundingClientRect();
                    const st = getComputedStyle(el);
                    return r.width > 2 && r.height > 2 && st.visibility !== 'hidden' && st.display !== 'none';
                });
                if (!candidates.length) return null;

                // Prefer a node whose text ends in the requested chapter number.
                // If the smallest leaf is only an icon, use its closest visible row.
                candidates.sort((a,b) => {
                    const at = norm(a.innerText || a.textContent);
                    const bt = norm(b.innerText || b.textContent);
                    const aa = a.getBoundingClientRect(), bb = b.getBoundingClientRect();
                    const aScore = (wanted.test(at) ? 0 : 1000000) + aa.width * aa.height;
                    const bScore = (wanted.test(bt) ? 0 : 1000000) + bb.width * bb.height;
                    return aScore - bScore;
                });
                let leaf = candidates[0];
                if (site === 'izib') {
                    const row = leaf.closest('li,[class*=track],[class*=playlist] > *,[class*=audio] > *,tr');
                    if (row) {
                        const rr = row.getBoundingClientRect();
                        const rs = getComputedStyle(row);
                        if (rr.width > 2 && rr.height > 2 && rs.visibility !== 'hidden' && rs.display !== 'none') leaf = row;
                    }
                }

                document.querySelectorAll('[data-oai-cdp-target]').forEach(el => el.removeAttribute('data-oai-cdp-target'));
                leaf.setAttribute('data-oai-cdp-target', '1');
                leaf.scrollIntoView({block:'center', inline:'nearest'});
                const r=leaf.getBoundingClientRect();
                const parent=leaf.parentElement;
                return {
                    text:norm(leaf.innerText || leaf.textContent).slice(0,220),
                    tag:leaf.tagName,
                    cls:String(leaf.className || '').slice(0,180),
                    parentTag:parent ? parent.tagName : '',
                    parentCls:parent ? String(parent.className || '').slice(0,180) : '',
                    x:r.x,y:r.y,width:r.width,height:r.height,
                    html:leaf.outerHTML.slice(0,700)
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
        locator = page.locator('[data-oai-cdp-target="1"]').first
        box = locator.bounding_box()
        if not box:
            return False, f"no-box:{target.get('text','')[:120]}"
        x = box["x"] + min(box["width"] * 0.35, max(12.0, box["width"] / 2))
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
                if index < 6 or misses <= 2:
                    diagnostics.append(f"target-{index}:miss:{info}")
                if misses >= 4:
                    break
                continue
            clicks += 1
            misses = 0
            if index < 10:
                diagnostics.append(f"target-{index}:hit:{info}")
            page.wait_for_timeout(500)

        diagnostics.insert(0, f"cdp-clicks={clicks}")
        diagnostics.insert(1, f"cdp-requests={request_count}")
        diagnostics.insert(2, f"cdp-resolvers={len(resolvers)}")
        diagnostics.insert(3, f"cdp-media={len(media)}")
        context.close()
        browser.close()
    return CdpCaptureResult(requests=request_count, media=media, resolvers=resolvers, diagnostics=diagnostics)
