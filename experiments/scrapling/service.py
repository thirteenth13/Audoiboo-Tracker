from __future__ import annotations

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, HttpUrl
from scrapling.fetchers import DynamicFetcher, Fetcher, StealthyFetcher

from media_detector import collect_from_text, collect_network_responses

app = FastAPI(title="Audioboo Scrapling experiment", version="0.1.0")


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


def _result(page, engine: str) -> ParseResponse:
    body = page.body.decode(getattr(page, "encoding", None) or "utf-8", errors="replace")
    xhr = _serialize_xhr(page)
    candidates = collect_from_text(body, str(page.url), "html") + collect_network_responses(xhr)
    dedup = {candidate.url: candidate for candidate in candidates}
    return ParseResponse(
        final_url=str(page.url),
        status=int(page.status),
        engine=engine,
        media=[{"url": c.url, "source": c.source} for c in dedup.values()],
        xhr_count=len(xhr),
    )


def _http(url: str) -> ParseResponse:
    return _result(Fetcher.get(url), "http")


def _dynamic(url: str, capture_xhr: str, wait_ms: int) -> ParseResponse:
    page = DynamicFetcher.fetch(url, headless=True, capture_xhr=capture_xhr, wait=wait_ms)
    return _result(page, "dynamic")


def _stealth(url: str, capture_xhr: str, wait_ms: int) -> ParseResponse:
    page = StealthyFetcher.fetch(url, headless=True, capture_xhr=capture_xhr, wait=wait_ms)
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
        return _stealth(url, req.capture_xhr, req.wait_ms)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"parser backend failed: {type(exc).__name__}: {exc}") from exc
