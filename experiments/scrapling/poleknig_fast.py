from __future__ import annotations

import html as html_lib
import re
from dataclasses import dataclass
from urllib.parse import parse_qsl, urlencode, urljoin, urlsplit, urlunsplit

import httpx

AUDIO_EXTENSIONS = (".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac")
FILES_RE = re.compile(r"(?:https?://[^\"'<>\s]+)?/files/(\d+)(?:\?[^\"'<>\s]*)?", re.I)
FILE_ID_RE = re.compile(r"(?:file(?:Id|_id)?|audio(?:Id|_id)?|track(?:Id|_id)?)[\"'\s:=]+[\"']?(\d{5,})", re.I)


@dataclass
class ResolveResult:
    requests: int
    media: list[str]
    discovered: list[str]
    statuses: list[str]


def _clean_markup(text: str) -> str:
    return html_lib.unescape(text).replace("\\/", "/").replace("\\u002F", "/")


def extract_file_urls(html: str, page_url: str) -> list[str]:
    """Extract Poleknig /files/<id> resolver URLs in page order."""
    text = _clean_markup(html)
    found: list[str] = []
    seen: set[str] = set()
    for match in FILES_RE.finditer(text):
        full = urljoin(page_url, match.group(0))
        if full not in seen:
            seen.add(full)
            found.append(full)

    # Some player builds keep only numeric ids in JS/data attributes.
    for match in FILE_ID_RE.finditer(text):
        full = urljoin(page_url, f"/files/{match.group(1)}")
        if full not in seen:
            seen.add(full)
            found.append(full)
    return found


def _is_audio_url(url: str) -> bool:
    return urlsplit(url).path.lower().endswith(AUDIO_EXTENSIONS)


def _variants(file_url: str, page_url: str) -> list[str]:
    """Keep the exact resolver first, then try the browser-like page query.

    DevTools shows /files/<id>?... requests. If HTML exposes a bare resolver,
    Poleknig may require the book/page query token that is already present on
    another resolver. We never invent authentication values.
    """
    out = [file_url]
    file_parts = urlsplit(file_url)
    page_parts = urlsplit(page_url)
    if not file_parts.query and page_parts.query:
        out.append(urlunsplit((file_parts.scheme, file_parts.netloc, file_parts.path, page_parts.query, "")))
    return list(dict.fromkeys(out))


def resolve(page_url: str, limit: int = 80, timeout: float = 8.0) -> ResolveResult:
    """Resolve Poleknig /files endpoints without downloading audio bodies."""
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.7",
        "Referer": "https://poleknig.com/",
    }
    resolved: list[str] = []
    seen_media: set[str] = set()
    requests = 0
    statuses: list[str] = []

    with httpx.Client(headers=headers, follow_redirects=False, timeout=timeout) as client:
        page = client.get(page_url)
        requests += 1
        page.raise_for_status()
        file_urls = extract_file_urls(page.text, page_url)

        # Do not fabricate a large id range. Only use exact resolver ids the
        # public page actually exposes; diagnostics tell us what CI received.
        for file_url in file_urls[:limit]:
            for candidate in _variants(file_url, page_url):
                try:
                    response = client.get(
                        candidate,
                        headers={
                            "Referer": page_url,
                            "Accept": "*/*",
                            "Sec-Fetch-Dest": "empty",
                            "Sec-Fetch-Mode": "cors",
                            "Sec-Fetch-Site": "same-origin",
                            "X-Requested-With": "XMLHttpRequest",
                        },
                    )
                    requests += 1
                except httpx.HTTPError as exc:
                    statuses.append(f"{urlsplit(candidate).path}:error:{type(exc).__name__}")
                    continue

                location = response.headers.get("location")
                content_type = response.headers.get("content-type", "").split(";", 1)[0]
                statuses.append(
                    f"{urlsplit(candidate).path}:{response.status_code}:{content_type or '-'}:location={'yes' if location else 'no'}"
                )
                if response.status_code in (301, 302, 303, 307, 308) and location:
                    target = urljoin(candidate, location)
                    if _is_audio_url(target) and target not in seen_media:
                        seen_media.add(target)
                        resolved.append(target)

    return ResolveResult(requests=requests, media=resolved, discovered=file_urls, statuses=statuses)
