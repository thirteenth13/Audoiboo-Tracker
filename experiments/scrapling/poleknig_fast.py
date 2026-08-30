from __future__ import annotations

import re
from urllib.parse import urljoin, urlsplit

import httpx

AUDIO_EXTENSIONS = (".mp3", ".m4a", ".m4b", ".aac", ".ogg", ".opus", ".flac")
FILES_RE = re.compile(r"(?:https?://[^\"'<>\s]+)?/files/(\d+)(?:\?[^\"'<>\s]*)?", re.I)


def extract_file_urls(html: str, page_url: str) -> list[str]:
    """Extract Poleknig /files/<id> resolver URLs in page order."""
    found: list[str] = []
    seen: set[str] = set()
    for match in FILES_RE.finditer(html.replace("\\/", "/")):
        full = urljoin(page_url, match.group(0))
        if full not in seen:
            seen.add(full)
            found.append(full)
    return found


def _is_audio_url(url: str) -> bool:
    return urlsplit(url).path.lower().endswith(AUDIO_EXTENSIONS)


def resolve(page_url: str, limit: int = 80, timeout: float = 8.0) -> tuple[int, list[str]]:
    """Resolve /files/<id> endpoints without downloading audiobook bodies.

    Poleknig answers these endpoints with 302 and puts the CDN MP3 in Location.
    We intentionally disable redirect following so the large 206 audio response is never read.
    """
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/151 Safari/537.36",
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.7",
        "Referer": "https://poleknig.com/",
    }
    resolved: list[str] = []
    seen_media: set[str] = set()
    requests = 0

    with httpx.Client(headers=headers, follow_redirects=False, timeout=timeout) as client:
        page = client.get(page_url)
        requests += 1
        page.raise_for_status()
        file_urls = extract_file_urls(page.text, page_url)

        # Some builds expose only the first resolver in HTML but use sequential ids.
        # If we see at least one id, probe a short consecutive window without following redirects.
        if file_urls:
            ids = [int(m.group(1)) for u in file_urls if (m := re.search(r"/files/(\d+)", u))]
            if ids:
                start = min(ids)
                known = {urljoin(page_url, f"/files/{i}") for i in range(start, start + min(limit, 60))}
                file_urls = list(dict.fromkeys(file_urls + sorted(known)))

        misses = 0
        for file_url in file_urls[:limit]:
            try:
                response = client.get(file_url, headers={"Referer": page_url})
                requests += 1
            except httpx.HTTPError:
                misses += 1
                if misses >= 4 and resolved:
                    break
                continue

            location = response.headers.get("location")
            if response.status_code in (301, 302, 303, 307, 308) and location:
                target = urljoin(file_url, location)
                if _is_audio_url(target) and target not in seen_media:
                    seen_media.add(target)
                    resolved.append(target)
                    misses = 0
                    continue

            misses += 1
            if misses >= 4 and resolved:
                break

    return requests, resolved
