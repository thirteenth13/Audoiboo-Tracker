from __future__ import annotations

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, HttpUrl
from scrapling.fetchers import DynamicFetcher, Fetcher, StealthyFetcher

from media_detector import collect_from_text, collect_network_responses

app = FastAPI(title="Audioboo Scrapling experiment", version="0.3.0")


class ParseRequest(BaseModel):
    url: HttpUrl
    mode: str = "auto"  # auto | http | dynamic | stealth
    capture_xhr: str = r".*"
    wait_ms: int = 1500


class ParseResponse(BaseModel):
    final_url: str
    status: int
    engine: str
    media: list[dict[str, str]]
    xhr_count: int = 0
    network_count: int = 0


def _serialize_xhr(page) -> list[dict]:
    result: list[dict] = []
    for item in getattr(page, "captured_xhr", []) or []:
        body = item.body
        if isinstance(body, bytes):
            body = body.decode(getattr(item, "encoding", None) or "utf-8", errors="replace")
        result.append({
            "url": str(item.url),
            "status": int(item.status),
            "headers": dict(item.headers or {}),
            "body": body if isinstance(body, str) else "",
        })
    return result


def _result(page, engine: str, network: list[dict] | None = None) -> ParseResponse:
    body = page.body.decode(getattr(page, "encoding", None) or "utf-8", errors="replace")
    xhr = _serialize_xhr(page)
    network = network or []
    candidates = (
        collect_from_text(body, str(page.url), "html")
        + collect_network_responses(xhr)
        + collect_network_responses(network)
    )
    dedup = {candidate.url: candidate for candidate in candidates}
    return ParseResponse(
        final_url=str(page.url),
        status=int(page.status),
        engine=engine,
        media=[{"url": c.url, "source": c.source} for c in dedup.values()],
        xhr_count=len(xhr),
        network_count=len(network),
    )


def _http(url: str) -> ParseResponse:
    return _result(Fetcher.get(url), "http")


def _dynamic(url: str, capture_xhr: str, wait_ms: int) -> ParseResponse:
    page = DynamicFetcher.fetch(
        url,
        headless=True,
        capture_xhr=capture_xhr,
        wait=wait_ms,
        timeout=20000,
        network_idle=False,
    )
    return _result(page, "dynamic")


def _activate_player(page) -> None:
    selectors = (
        "button[aria-label*='play' i]",
        "[role='button'][aria-label*='play' i]",
        ".player-play",
        ".play-button",
        ".jp-play",
        ".plyr__control[data-plyr='play']",
        "[class*='player'] [class*='play']",
        "button[class*='play']",
    )
    for selector in selectors:
        try:
            locator = page.locator(selector)
            if locator.count() > 0 and locator.first.is_visible():
                locator.first.click(timeout=1200, force=True)
                break
        except Exception:
            continue

    try:
        page.evaluate(
            """() => {
                for (const media of document.querySelectorAll('audio,video')) {
                    try {
                        const promise = media.play();
                        if (promise && promise.catch) promise.catch(() => {});
                    } catch (_) {}
                }
            }"""
        )
    except Exception:
        pass
    page.wait_for_timeout(2200)


def _dynamic_interactive(url: str, capture_xhr: str, wait_ms: int = 1200) -> ParseResponse:
    page = DynamicFetcher.fetch(
        url,
        headless=True,
        capture_xhr=capture_xhr,
        page_action=_activate_player,
        wait=wait_ms,
        timeout=20000,
        network_idle=False,
    )
    return _result(page, "dynamic-interactive")


def _dynamic_network(url: str, capture_xhr: str, wait_ms: int = 1200, activate: bool = True) -> ParseResponse:
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

    page = DynamicFetcher.fetch(
        url,
        headless=True,
        capture_xhr=capture_xhr,
        page_setup=setup,
        page_action=_activate_player if activate else None,
        wait=wait_ms,
        timeout=20000,
        network_idle=False,
    )
    return _result(page, "dynamic-network", network)


def _stealth(url: str, capture_xhr: str, wait_ms: int) -> ParseResponse:
    page = StealthyFetcher.fetch(
        url,
        headless=True,
        capture_xhr=capture_xhr,
        wait=wait_ms,
        timeout=20000,
        network_idle=False,
    )
    return _result(page, "stealth")


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/parse", response_model=ParseResponse)
def parse(req: ParseRequest) -> ParseResponse:
    url = str(req.url)
    try:
        if req.mode == "http":
            return _http(url)
        if req.mode == "dynamic":
            return _dynamic(url, req.capture_xhr, req.wait_ms)
        if req.mode == "stealth":
            return _stealth(url, req.capture_xhr, req.wait_ms)
        if req.mode != "auto":
            raise HTTPException(status_code=400, detail="mode must be auto/http/dynamic/stealth")

        first = _http(url)
        if first.media:
            return first
        second = _dynamic(url, req.capture_xhr, req.wait_ms)
        if second.media:
            return second
        third = _dynamic_interactive(url, req.capture_xhr, req.wait_ms)
        if third.media:
            return third
        fourth = _dynamic_network(url, req.capture_xhr, req.wait_ms, activate=True)
        if fourth.media:
            return fourth
        return _stealth(url, req.capture_xhr, req.wait_ms)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"parser backend failed: {type(exc).__name__}: {exc}") from exc
