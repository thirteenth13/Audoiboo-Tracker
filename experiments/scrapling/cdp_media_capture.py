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
    """Find a visible player row and mark it without synthesizing a JS click."""
    try:
        return page.evaluate(
            r"""({site,index}) => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                const chapter = String(index + 1).padStart(2, '0');
                let wanted;
                if (site === 'poleknig') {
                    wanted = new RegExp('^' + chapter + '$');
                } else if (site === 'izib') {
                    // Visible rows are e.g. "Звёздная кровь 11. Колония Альфа 02"
                    // and the duration may either be inside the row or a sibling.
                    wanted = new RegExp('(?:^|\\s)' + chapter + '(?:\\s+\\d{1,2}:\\d{2})?$');
                } else {
                    // Do not depend on the localized book title. Knigavuhe track
                    // labels reliably end in _0, _1, _2 ...; duration may follow.
                    wanted = new RegExp('_' + index + '(?:\\s+\\d{1,2}:\\d{2})?$');
                }

                const all = Array.from(document.querySelectorAll('body *'));
                let candidates = all.filter(el => wanted.test(norm(el.innerText || el.textContent)));

                // Prefer the smallest matching descendant so the whole page/player
                // container cannot win merely because its aggregate text matches.
                const leaves = candidates.filter(el => !Array.from(el.children).some(c => wanted.test(norm(c.innerText || c.textContent))));
                if (leaves.length) candidates = leaves;
                candidates = candidates.filter(el => {
                    const r = el.getBoundingClientRect();
                    const st = getComputedStyle(el);
                    return r.width > 2 && r.height > 2 && st.visibility !== 'hidden' && st.display !== 'none';
                });
                if (!candidates.length) return null;

                candidates.sort((a,b) => {
                    const aa = a.getBoundingClientRect(), bb = b.getBoundingClientRect();
                    return aa.width * aa.height - bb.width * bb.height;
                });
                let leaf = candidates[0];

                // On both sites the click handler can live on a surrounding row.
                // Climb only to a nearby player-like ancestor, never to a broad
                // document container.
                if (site === 'izib' || site === 'knigavuhe') {
                    const row = leaf.closest('li,tr,[role=row],[class*=track],[class*=playlist] > *,[class*=audio] > *,[class*=item]');
                    if (row) {
                        const rr = row.getBoundingClientRect();
                        const rs = getComputedStyle(row);
                        const rowText = norm(row.innerText || row.textContent);
                        if (rr.width > 2 && rr.height > 2 && rr.height < 140 && rs.visibility !== 'hidden' && rs.display !== 'none' && wanted.test(rowText)) {
                            leaf = row;
                        }
                    }
                }

                document.querySelectorAll('[data-oai-cdp-target]').forEach(el => el.removeAttribute('data-oai-cdp-target'));
                leaf.setAttribute('data-oai-cdp-target', '1');
                leaf.scrollIntoView({block:'center', inline:'nearest'});
                const r = leaf.getBoundingClientRect();
                const parent = leaf.parentElement;
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


def _visible_track_hints(page, site: str) -> list[str]:
    """Bounded diagnostics for selector misses; contains visible text only."""
    try:
        return page.evaluate(
            r"""site => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                const out = [];
                for (const el of Array.from(document.querySelectorAll('body *'))) {
                    const t = norm(el.innerText || el.textContent);
                    if (!t || t.length > 180) continue;
                    const r = el.getBoundingClientRect();
                    if (r.width < 3 || r.height < 3 || r.height > 120) continue;
                    const likely = site === 'knigavuhe' ? /_\d+(?:\s|$)/.test(t) : /(?:^|\s)\d{2}(?:\s+\d{1,2}:\d{2})?$/.test(t);
                    if (likely && !out.includes(t)) out.push(t);
                    if (out.length >= 12) break;
                }
                return out;
            }""",
            site,
        )
    except Exception:
        return []


def _trusted_click(page, site: str, index: int) -> tuple[bool, str]:
    target = _mark_track_target(page, site, index)
    if not target:
        return False, "not-found"
    try:
        locator = page.locator('[data-oai-cdp-target="1"]').first
        box = locator.bounding_box()
        if not box:
            return False, f"no-box:{target.get('text','')[:120]}"
        # Hit the left third where both players expose their play icon/title.
        x = box["x"] + min(box["width"] * 0.25, max(14.0, box["width"] / 2))
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
                if old_url and location and ((site == "poleknig" and "/files/" in old_url) or _audio(str(location))):
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
        page.wait_for_timeout(1400)

        if site in {"knigavuhe", "izib"}:
            hints = _visible_track_hints(page, site)
            diagnostics.append(f"visible-track-hints={len(hints)}:{str(hints)[:1400]}")

        clicks = 0
        misses = 0
        for index in range(max_tracks):
            ok, info = _trusted_click(page, site, index)
            if not ok:
                misses += 1
                if index < 8 or misses <= 2:
                    diagnostics.append(f"target-{index}:miss:{info}")
                if misses >= 4:
                    break
                continue
            clicks += 1
            misses = 0
            if index < 12:
                diagnostics.append(f"target-{index}:hit:{info}")
            page.wait_for_timeout(550)

        diagnostics.insert(0, f"cdp-clicks={clicks}")
        diagnostics.insert(1, f"cdp-requests={request_count}")
        diagnostics.insert(2, f"cdp-resolvers={len(resolvers)}")
        diagnostics.insert(3, f"cdp-media={len(media)}")
        context.close()
        browser.close()
    return CdpCaptureResult(requests=request_count, media=media, resolvers=resolvers, diagnostics=diagnostics)
