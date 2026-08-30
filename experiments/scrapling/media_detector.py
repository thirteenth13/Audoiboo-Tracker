from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Iterable
from urllib.parse import urljoin

MEDIA_EXTENSIONS = (".mp3", ".m4b", ".m4a", ".aac", ".ogg", ".opus", ".flac", ".m3u8", ".zip", ".rar")
URL_RE = re.compile(r"https?://[^\s\"'<>]+", re.IGNORECASE)


@dataclass(frozen=True)
class MediaCandidate:
    url: str
    source: str


def looks_like_media(url: str, content_type: str | None = None) -> bool:
    clean = url.lower().split("?", 1)[0].split("#", 1)[0]
    if clean.endswith(MEDIA_EXTENSIONS):
        return True
    ctype = (content_type or "").lower()
    return (
        ctype.startswith("audio/")
        or "application/vnd.apple.mpegurl" in ctype
        or "application/x-mpegurl" in ctype
        or "application/zip" in ctype
        or "application/x-rar" in ctype
    )


def collect_from_text(text: str, base_url: str, source: str = "html") -> list[MediaCandidate]:
    out: list[MediaCandidate] = []
    seen: set[str] = set()
    for raw in URL_RE.findall(text or ""):
        url = urljoin(base_url, raw.replace("\\/", "/"))
        if looks_like_media(url) and url not in seen:
            seen.add(url)
            out.append(MediaCandidate(url=url, source=source))
    return out


def collect_network_responses(responses: Iterable[dict]) -> list[MediaCandidate]:
    out: list[MediaCandidate] = []
    seen: set[str] = set()
    for item in responses:
        url = str(item.get("url") or "")
        headers = item.get("headers") or {}
        ctype = headers.get("content-type") or headers.get("Content-Type")
        if url and looks_like_media(url, ctype) and url not in seen:
            seen.add(url)
            out.append(MediaCandidate(url=url, source="network"))
        body = item.get("body")
        if isinstance(body, str):
            for candidate in collect_from_text(body, url or "https://invalid/", source="xhr-body"):
                if candidate.url not in seen:
                    seen.add(candidate.url)
                    out.append(candidate)
    return out
