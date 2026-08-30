from __future__ import annotations

import re
from dataclasses import dataclass
from urllib.parse import urljoin, urlsplit

from playwright.sync_api import sync_playwright

AUDIO_RE = re.compile(r"\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:$|\?)", re.I)
AUDIO_URL_RE = re.compile(r"https?://[^\"'<>\s]+\.(?:mp3|m4a|m4b|aac|ogg|opus|flac)(?:\?[^\"'<>\s]*)?", re.I)
KNIGAVUHE_INTEREST_RE = re.compile(r"(?:/play/|/audio/|18350|book|player|playlist|track)", re.I)


@dataclass
class CdpCaptureResult:
    requests: int
    media: list[str]
    resolvers: list[str]
    diagnostics: list[str]


def _audio(url: str) -> bool:
    return bool(AUDIO_RE.search(urlsplit(url).path))


def _mark_text_target(page, patterns: list[str]) -> dict | None:
    try:
        return page.evaluate(
            r"""patterns => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                const regs = patterns.map(p => new RegExp(p, 'i'));
                const matches = t => regs.some(r => r.test(norm(t)));
                let candidates = Array.from(document.querySelectorAll('body *')).filter(el => matches(el.innerText || el.textContent));
                const leaves = candidates.filter(el => !Array.from(el.children).some(c => matches(c.innerText || c.textContent)));
                if (leaves.length) candidates = leaves;
                candidates = candidates.filter(el => {
                    const r = el.getBoundingClientRect(), st = getComputedStyle(el);
                    return r.width > 4 && r.height > 4 && r.height < 180 && st.visibility !== 'hidden' && st.display !== 'none';
                });
                if (!candidates.length) return null;
                candidates.sort((a,b) => {
                    const ac = /BUTTON|A/.test(a.tagName) || a.getAttribute('role') === 'button' ? -1 : 0;
                    const bc = /BUTTON|A/.test(b.tagName) || b.getAttribute('role') === 'button' ? -1 : 0;
                    if (ac !== bc) return ac - bc;
                    const ar=a.getBoundingClientRect(), br=b.getBoundingClientRect();
                    return ar.width*ar.height - br.width*br.height;
                });
                let el = candidates[0];
                const clickable = el.closest('button,a,[role=button],[onclick]');
                if (clickable) el = clickable;
                document.querySelectorAll('[data-oai-cdp-action]').forEach(x => x.removeAttribute('data-oai-cdp-action'));
                el.setAttribute('data-oai-cdp-action','1');
                el.scrollIntoView({block:'center', inline:'nearest'});
                const r=el.getBoundingClientRect();
                return {text:norm(el.innerText || el.textContent).slice(0,220), tag:el.tagName, cls:String(el.className || '').slice(0,160), x:r.x,y:r.y,width:r.width,height:r.height};
            }""",
            patterns,
        )
    except Exception:
        return None


def _trusted_action_click(page, patterns: list[str]) -> tuple[bool, str]:
    target = _mark_text_target(page, patterns)
    if not target:
        return False, "not-found"
    try:
        box = page.locator('[data-oai-cdp-action="1"]').first.bounding_box()
        if not box:
            return False, f"no-box:{target.get('text','')[:120]}"
        x = box["x"] + min(max(16.0, box["width"] * 0.18), box["width"] / 2)
        y = box["y"] + box["height"] / 2
        page.mouse.move(x, y)
        page.mouse.down()
        page.wait_for_timeout(45)
        page.mouse.up()
        return True, f"{target.get('text','')}:{target.get('tag','')}:{target.get('cls','')[:100]}"
    except Exception as exc:
        return False, f"click-error:{type(exc).__name__}"


def _visible_track_hints(page, site: str) -> list[str]:
    try:
        return page.evaluate(
            r"""site => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                const out = [];
                for (const el of Array.from(document.querySelectorAll('body *'))) {
                    const t = norm(el.innerText || el.textContent);
                    if (!t || t.length > 220) continue;
                    const r = el.getBoundingClientRect();
                    if (r.width < 3 || r.height < 3 || r.height > 130) continue;
                    let likely = false;
                    if (site === 'knigavuhe') likely = /_\d+(?:\s|$)/.test(t) || /^\d{2}(?:\s+\d{1,2}:\d{2})?$/.test(t);
                    else likely = /(?:^|\s)\d{2}(?:\s+\d{1,2}:\d{2})?$/.test(t);
                    if (likely && !out.includes(t)) out.push(t);
                    if (out.length >= 20) break;
                }
                return out;
            }""",
            site,
        )
    except Exception:
        return []


def _expand_knigavuhe_player(page, diagnostics: list[str]) -> None:
    ok, info = _trusted_action_click(page, [r"^Понятно$", r"^OK$"])
    if ok:
        diagnostics.append(f"kv-cookie-dismiss:{info}")
        page.wait_for_timeout(250)

    before = _visible_track_hints(page, "knigavuhe")
    if len(before) >= 2:
        diagnostics.append(f"kv-player-already-expanded:{len(before)}")
        return

    ok, info = _trusted_action_click(page, [r"^Слушать полностью$", r"Слушать полностью"])
    diagnostics.append(f"kv-expand:{'hit' if ok else 'miss'}:{info}")
    if not ok:
        return

    for waited in range(0, 5000, 250):
        page.wait_for_timeout(250)
        hints = _visible_track_hints(page, "knigavuhe")
        if len(hints) >= 2:
            diagnostics.append(f"kv-expanded-after={waited + 250}ms:hints={len(hints)}")
            return
    diagnostics.append(f"kv-expand-timeout:hints={len(_visible_track_hints(page, 'knigavuhe'))}")


def _knigavuhe_book_id(page) -> str | None:
    try:
        html = page.content()
    except Exception:
        return None
    for pattern in (r"/play/id/(\d+)", r"/covers/(\d+)/", r"book[_-]?id[^0-9]{0,20}(\d{4,})"):
        m = re.search(pattern, html, re.I)
        if m:
            return m.group(1)
    return None


def _probe_knigavuhe_play_api(context, page, page_url: str, diagnostics: list[str], remember) -> int:
    """Probe the public player endpoint seen in the browser before relying on DOM expansion."""
    book_id = _knigavuhe_book_id(page)
    if not book_id:
        diagnostics.append("kv-play-api:book-id-miss")
        return 0
    endpoint = urljoin(page_url, f"/play/id/{book_id}")
    try:
        response = context.request.get(
            endpoint,
            headers={
                "Referer": page_url,
                "X-Requested-With": "XMLHttpRequest",
                "Accept": "application/json,text/plain,*/*",
            },
            timeout=10000,
        )
        text = response.text()
    except Exception as exc:
        diagnostics.append(f"kv-play-api:error:{type(exc).__name__}")
        return 0

    cleaned = str(text).replace("\\/", "/").replace("&amp;", "&")
    urls: list[str] = []
    for match in AUDIO_URL_RE.finditer(cleaned):
        url = match.group(0)
        if url not in urls:
            urls.append(url)
            remember(url, "Media", "play-api")
    ctype = response.headers.get("content-type", "") if response.headers else ""
    diagnostics.append(f"kv-play-api:{response.status}:id={book_id}:ctype={ctype}:audio={len(urls)}")
    compact = re.sub(r"\s+", " ", cleaned).strip()
    if compact:
        diagnostics.append(f"kv-play-body:{compact[:1800]}")
    return len(urls)


def _mark_track_target(page, site: str, index: int) -> dict | None:
    try:
        return page.evaluate(
            r"""({site,index}) => {
                const norm = s => (s || '').replace(/\s+/g, ' ').trim();
                const chapter1 = String(index + 1).padStart(2, '0');
                const chapter0 = String(index).padStart(2, '0');
                const matches = (text) => {
                    const t = norm(text);
                    if (!t) return false;
                    if (site === 'poleknig') return t === chapter1;
                    if (site === 'izib') return new RegExp('(?:^|\\s)' + chapter1 + '(?:\\s+\\d{1,2}:\\d{2})?$').test(t);
                    if (site === 'knigavuhe') {
                        if (new RegExp('_' + index + '(?:\\s+\\d{1,2}:\\d{2})?$').test(t)) return true;
                        if (t === chapter0) return true;
                        if (new RegExp('^' + chapter0 + '\\s+\\d{1,2}:\\d{2}$').test(t)) return true;
                    }
                    return false;
                };
                const all = Array.from(document.querySelectorAll('body *'));
                let candidates = all.filter(el => matches(el.innerText || el.textContent));
                const leaves = candidates.filter(el => !Array.from(el.children).some(c => matches(c.innerText || c.textContent)));
                if (leaves.length) candidates = leaves;
                candidates = candidates.filter(el => {
                    const r = el.getBoundingClientRect(), st = getComputedStyle(el);
                    return r.width > 2 && r.height > 2 && r.height < 160 && st.visibility !== 'hidden' && st.display !== 'none';
                });
                if (!candidates.length) return null;
                candidates.sort((a,b) => {
                    const aa = a.getBoundingClientRect(), bb = b.getBoundingClientRect();
                    return aa.width * aa.height - bb.width * bb.height;
                });
                let leaf = candidates[0];
                if (site === 'izib' || site === 'knigavuhe') {
                    const ancestors = [];
                    let p = leaf;
                    for (let depth=0; p && depth<6; depth++, p=p.parentElement) {
                        const r = p.getBoundingClientRect(), st = getComputedStyle(p);
                        if (r.width > 20 && r.height > 8 && r.height < 130 && st.visibility !== 'hidden' && st.display !== 'none') ancestors.push(p);
                    }
                    for (const row of ancestors) {
                        const rowText = norm(row.innerText || row.textContent);
                        if (site === 'izib' && new RegExp('(?:^|\\s)' + chapter1 + '(?:\\s|$)').test(rowText)) { leaf = row; break; }
                        if (site === 'knigavuhe' && (new RegExp('_' + index + '(?:\\s|$)').test(rowText) || new RegExp('(?:^|\\s)' + chapter0 + '(?:\\s|$)').test(rowText))) { leaf = row; break; }
                    }
                }
                document.querySelectorAll('[data-oai-cdp-target]').forEach(el => el.removeAttribute('data-oai-cdp-target'));
                leaf.setAttribute('data-oai-cdp-target', '1');
                leaf.scrollIntoView({block:'center', inline:'nearest'});
                const r = leaf.getBoundingClientRect(), parent = leaf.parentElement;
                return {text:norm(leaf.innerText || leaf.textContent).slice(0,260), tag:leaf.tagName, cls:String(leaf.className || '').slice(0,180), parentTag:parent ? parent.tagName : '', x:r.x,y:r.y,width:r.width,height:r.height};
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
        x = box["x"] + min(max(14.0, box["width"] * 0.12), box["width"] / 2)
        y = box["y"] + box["height"] / 2
        page.mouse.move(x, y)
        page.mouse.down()
        page.wait_for_timeout(35)
        page.mouse.up()
        return True, f"{target.get('text','')}:{target.get('tag','')}:{target.get('parentTag','')}:{target.get('cls','')[:100]}"
    except Exception as exc:
        return False, f"click-error:{type(exc).__name__}"


def capture(page_url: str, site: str, max_tracks: int = 30, timeout_ms: int = 30000) -> CdpCaptureResult:
    media: list[str] = []
    resolvers: list[str] = []
    seen_media: set[str] = set()
    seen_resolvers: set[str] = set()
    diagnostics: list[str] = []
    request_count = 0
    response_meta: dict[str, tuple[str, str, int]] = {}

    def remember(url: str, resource_type: str = "", source: str = "") -> None:
        path = urlsplit(url).path
        host = urlsplit(url).hostname or ""
        if site == "poleknig" and host.endswith("poleknig.com") and path.startswith("/files/") and url not in seen_resolvers:
            seen_resolvers.add(url)
            resolvers.append(url)
        if _audio(url) or resource_type.lower() == "media":
            if url.startswith("http") and url not in seen_media:
                seen_media.add(url)
                media.append(url)
                diagnostics.append(f"media-{source}:{resource_type}:{url[:900]}")

    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True)
        context = browser.new_context(user_agent="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36", locale="ru-RU")
        page = context.new_page()
        session = context.new_cdp_session(page)
        session.send("Network.enable", {"maxTotalBufferSize": 3000000, "maxResourceBufferSize": 500000})

        def request_event(params) -> None:
            nonlocal request_count
            request_count += 1
            req = params.get("request", {})
            url = str(req.get("url", ""))
            typ = str(params.get("type", ""))
            remember(url, typ, "request")
            if site == "knigavuhe" and KNIGAVUHE_INTEREST_RE.search(url):
                diagnostics.append(f"kv-request:{typ}:{url[:1000]}")
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
            rid = str(params.get("requestId", ""))
            mime = str(response.get("mimeType", ""))
            status = int(response.get("status", 0) or 0)
            response_meta[rid] = (url, mime, status)
            remember(url, typ, "response")
            if site == "knigavuhe" and KNIGAVUHE_INTEREST_RE.search(url):
                diagnostics.append(f"kv-response:{status}:{typ}:{mime}:{url[:900]}")
            if site == "poleknig" and urlsplit(url).path.startswith("/files/"):
                headers = response.get("headers") or {}
                location = headers.get("location") or headers.get("Location")
                diagnostics.append(f"resolver-response:{status}:location={'yes' if location else 'no'}:{url[:700]}")
                if location:
                    remember(str(location), "Media" if _audio(str(location)) else "", "location")

        def loading_finished(params) -> None:
            if site != "knigavuhe":
                return
            rid = str(params.get("requestId", ""))
            meta = response_meta.get(rid)
            if not meta:
                return
            url, mime, status = meta
            if not KNIGAVUHE_INTEREST_RE.search(url) or not any(x in mime.lower() for x in ("json", "javascript", "text", "html")):
                return
            try:
                body = session.send("Network.getResponseBody", {"requestId": rid}).get("body", "")
            except Exception as exc:
                diagnostics.append(f"kv-body-error:{type(exc).__name__}:{url[:500]}")
                return
            clean = re.sub(r"\s+", " ", str(body)).strip()
            if clean:
                diagnostics.append(f"kv-body:{status}:{mime}:{url[:500]}::{clean[:3500]}")

        session.on("Network.requestWillBeSent", request_event)
        session.on("Network.responseReceived", response_event)
        session.on("Network.loadingFinished", loading_finished)
        page.goto(page_url, wait_until="domcontentloaded", timeout=timeout_ms)
        page.wait_for_timeout(2200)

        if site == "knigavuhe":
            api_audio = _probe_knigavuhe_play_api(context, page, page_url, diagnostics, remember)
            if not api_audio:
                _expand_knigavuhe_player(page, diagnostics)

        if site in {"knigavuhe", "izib"}:
            hints = _visible_track_hints(page, site)
            diagnostics.append(f"visible-track-hints={len(hints)}:{str(hints)[:2200]}")

        if site == "knigavuhe":
            try:
                state = page.evaluate(r"""() => ({
                    title: document.title,
                    bodyText: (document.body.innerText || '').replace(/\s+/g,' ').slice(0,2400),
                    scripts: Array.from(document.scripts).map(s => s.src || '').filter(Boolean).filter(x => /common|player|book|audio|play/i.test(x)).slice(0,20),
                    links: Array.from(document.querySelectorAll('a[href]')).map(a => a.href).filter(x => /play|audio|18350/i.test(x)).slice(0,20),
                    segmentToggle: Array.from(document.querySelectorAll('body *')).map(el => (el.innerText || el.textContent || '').replace(/\s+/g,' ').trim()).filter(t => /Большие отрезки|Больше отрезки|По главам/i.test(t)).slice(0,12)
                })""")
                diagnostics.append(f"kv-dom-state:{str(state)[:6000]}")
            except Exception as exc:
                diagnostics.append(f"kv-dom-state-error:{type(exc).__name__}")

        clicks = 0
        misses = 0
        if not (site == "knigavuhe" and media):
            for index in range(max_tracks):
                ok, info = _trusted_click(page, site, index)
                if not ok:
                    misses += 1
                    if index < 10 or misses <= 2:
                        diagnostics.append(f"target-{index}:miss:{info}")
                    if misses >= 4:
                        break
                    continue
                clicks += 1
                misses = 0
                if index < 14:
                    diagnostics.append(f"target-{index}:hit:{info}")
                page.wait_for_timeout(550)

        diagnostics.insert(0, f"cdp-clicks={clicks}")
        diagnostics.insert(1, f"cdp-requests={request_count}")
        diagnostics.insert(2, f"cdp-resolvers={len(resolvers)}")
        diagnostics.insert(3, f"cdp-media={len(media)}")
        context.close()
        browser.close()
    return CdpCaptureResult(requests=request_count, media=media, resolvers=resolvers, diagnostics=diagnostics)
