from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright

AUDIO_RE = re.compile(r"\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:$|\?)", re.I)
KNIGAVUHE_INTEREST_RE = re.compile(r"(?:/play/|/audio/|18350|book|player|playlist|track)", re.I)


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
                if (site === 'poleknig') wanted = new RegExp('^' + chapter + '$');
                else if (site === 'izib') wanted = new RegExp('(?:^|\\s)' + chapter + '(?:\\s+\\d{1,2}:\\d{2})?$');
                else wanted = new RegExp('_' + index + '(?:\\s+\\d{1,2}:\\d{2})?$');

                const all = Array.from(document.querySelectorAll('body *'));
                let candidates = all.filter(el => wanted.test(norm(el.innerText || el.textContent)));
                const leaves = candidates.filter(el => !Array.from(el.children).some(c => wanted.test(norm(c.innerText || c.textContent))));
                if (leaves.length) candidates = leaves;
                candidates = candidates.filter(el => {
                    const r = el.getBoundingClientRect(), st = getComputedStyle(el);
                    return r.width > 2 && r.height > 2 && st.visibility !== 'hidden' && st.display !== 'none';
                });
                if (!candidates.length) return null;
                candidates.sort((a,b) => {
                    const aa = a.getBoundingClientRect(), bb = b.getBoundingClientRect();
                    return aa.width * aa.height - bb.width * bb.height;
                });
                let leaf = candidates[0];
                if (site === 'izib' || site === 'knigavuhe') {
                    const row = leaf.closest('li,tr,[role=row],[class*=track],[class*=playlist] > *,[class*=audio] > *,[class*=item]');
                    if (row) {
                        const rr = row.getBoundingClientRect(), rs = getComputedStyle(row), rowText = norm(row.innerText || row.textContent);
                        if (rr.width > 2 && rr.height > 2 && rr.height < 140 && rs.visibility !== 'hidden' && rs.display !== 'none' && wanted.test(rowText)) leaf = row;
                    }
                }
                document.querySelectorAll('[data-oai-cdp-target]').forEach(el => el.removeAttribute('data-oai-cdp-target'));
                leaf.setAttribute('data-oai-cdp-target', '1');
                leaf.scrollIntoView({block:'center', inline:'nearest'});
                const r = leaf.getBoundingClientRect(), parent = leaf.parentElement;
                return {text:norm(leaf.innerText || leaf.textContent).slice(0,220), tag:leaf.tagName, cls:String(leaf.className || '').slice(0,180), parentTag:parent ? parent.tagName : '', x:r.x,y:r.y,width:r.width,height:r.height};
            }""",
            {"site": site, "index": index},
        )
    except Exception:
        return None


def _visible_track_hints(page, site: str) -> list[str]:
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
        x = box["x"] + min(box["width"] * 0.25, max(14.0, box["width"] / 2))
        y = box["y"] + box["height"] / 2
        page.mouse.move(x, y); page.mouse.down(); page.wait_for_timeout(35); page.mouse.up()
        return True, f"{target.get('text','')}:{target.get('tag','')}:{target.get('parentTag','')}:{target.get('cls','')[:100]}"
    except Exception as exc:
        return False, f"click-error:{type(exc).__name__}"


def capture(page_url: str, site: str, max_tracks: int = 30, timeout_ms: int = 30000) -> CdpCaptureResult:
    media: list[str] = []; resolvers: list[str] = []
    seen_media: set[str] = set(); seen_resolvers: set[str] = set(); diagnostics: list[str] = []
    request_count = 0
    response_meta: dict[str, tuple[str, str, int]] = {}

    def remember(url: str, resource_type: str = "", source: str = "") -> None:
        path = urlsplit(url).path; host = urlsplit(url).hostname or ""
        if site == "poleknig" and host.endswith("poleknig.com") and path.startswith("/files/") and url not in seen_resolvers:
            seen_resolvers.add(url); resolvers.append(url)
        if _audio(url) or resource_type.lower() == "media":
            if url.startswith("http") and url not in seen_media:
                seen_media.add(url); media.append(url); diagnostics.append(f"media-{source}:{resource_type}:{url[:900]}")

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36", locale="ru-RU")
        page = context.new_page(); session = context.new_cdp_session(page)
        session.send("Network.enable", {"maxTotalBufferSize": 3000000, "maxResourceBufferSize": 500000})

        def request_event(params) -> None:
            nonlocal request_count
            request_count += 1
            req = params.get("request", {}); url = str(req.get("url", "")); typ = str(params.get("type", ""))
            remember(url, typ, "request")
            if site == "knigavuhe" and KNIGAVUHE_INTEREST_RE.search(url):
                diagnostics.append(f"kv-request:{typ}:{url[:1000]}")
            redirect = params.get("redirectResponse") or {}
            if redirect:
                old_url = str(redirect.get("url", "")); headers = redirect.get("headers") or {}; location = headers.get("location") or headers.get("Location")
                if old_url and location and ((site == "poleknig" and "/files/" in old_url) or _audio(str(location))):
                    diagnostics.append(f"redirect:{redirect.get('status')}:{old_url[:500]} -> {str(location)[:700]}")
                    remember(str(location), "Media" if _audio(str(location)) else "", "redirect")

        def response_event(params) -> None:
            response = params.get("response", {}); url = str(response.get("url", "")); typ = str(params.get("type", "")); rid = str(params.get("requestId", ""))
            mime = str(response.get("mimeType", "")); status = int(response.get("status", 0) or 0)
            response_meta[rid] = (url, mime, status)
            remember(url, typ, "response")
            if site == "knigavuhe" and KNIGAVUHE_INTEREST_RE.search(url):
                diagnostics.append(f"kv-response:{status}:{typ}:{mime}:{url[:900]}")
            if site == "poleknig" and urlsplit(url).path.startswith("/files/"):
                headers = response.get("headers") or {}; location = headers.get("location") or headers.get("Location")
                diagnostics.append(f"resolver-response:{status}:location={'yes' if location else 'no'}:{url[:700]}")
                if location: remember(str(location), "Media" if _audio(str(location)) else "", "location")

        def loading_finished(params) -> None:
            if site != "knigavuhe": return
            rid = str(params.get("requestId", "")); meta = response_meta.get(rid)
            if not meta: return
            url, mime, status = meta
            if not KNIGAVUHE_INTEREST_RE.search(url): return
            if not any(x in mime.lower() for x in ("json", "javascript", "text", "html")): return
            try:
                body = session.send("Network.getResponseBody", {"requestId": rid}).get("body", "")
            except Exception as exc:
                diagnostics.append(f"kv-body-error:{type(exc).__name__}:{url[:500]}"); return
            clean = re.sub(r"\s+", " ", str(body)).strip()
            if clean:
                diagnostics.append(f"kv-body:{status}:{mime}:{url[:500]}::{clean[:3500]}")

        session.on("Network.requestWillBeSent", request_event)
        session.on("Network.responseReceived", response_event)
        session.on("Network.loadingFinished", loading_finished)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms); page.wait_for_timeout(2200)

        if site in {"knigavuhe", "izib"}:
            hints = _visible_track_hints(page, site); diagnostics.append(f"visible-track-hints={len(hints)}:{str(hints)[:1400]}")
        if site == "knigavuhe":
            try:
                state = page.evaluate(r"""() => ({
                    title: document.title,
                    bodyText: (document.body.innerText || '').replace(/\s+/g,' ').slice(0,1800),
                    scripts: Array.from(document.scripts).map(s => s.src || '').filter(Boolean).filter(x => /common|player|book|audio|play/i.test(x)).slice(0,20),
                    links: Array.from(document.querySelectorAll('a[href]')).map(a => a.href).filter(x => /play|audio|18350/i.test(x)).slice(0,20)
                })""")
                diagnostics.append(f"kv-dom-state:{str(state)[:5000]}")
            except Exception as exc:
                diagnostics.append(f"kv-dom-state-error:{type(exc).__name__}")

        clicks = 0; misses = 0
        for index in range(max_tracks):
            ok, info = _trusted_click(page, site, index)
            if not ok:
                misses += 1
                if index < 8 or misses <= 2: diagnostics.append(f"target-{index}:miss:{info}")
                if misses >= 4: break
                continue
            clicks += 1; misses = 0
            if index < 12: diagnostics.append(f"target-{index}:hit:{info}")
            page.wait_for_timeout(550)

        diagnostics.insert(0, f"cdp-clicks={clicks}"); diagnostics.insert(1, f"cdp-requests={request_count}"); diagnostics.insert(2, f"cdp-resolvers={len(resolvers)}"); diagnostics.insert(3, f"cdp-media={len(media)}")
        context.close(); browser.close()
    return CdpCaptureResult(requests=request_count, media=media, resolvers=resolvers, diagnostics=diagnostics)
