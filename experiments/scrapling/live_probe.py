from __future__ import annotations

import json
import sys
from dataclasses import asdict, dataclass

from service import _dynamic, _http


TARGETS = [
    ("baza-knig", "https://baza-knig.info/audio-115251-zvezdnaja-krov-10-prozrachnye-dorogi-roman-prokofev"),
    ("poleknig", "https://poleknig.com/books/212841"),
    ("lis10book", "https://lis10book.com/audio/dlan-sistemy-kniga-3/"),
    ("knigavuhe", "https://m.knigavuhe.org/book/igra-kota-kniga-vtoraja/"),
    ("izib", "https://pda.izib.uk/art141591"),
]


@dataclass
class ProbeResult:
    name: str
    url: str
    http_status: int | None = None
    http_media: int = 0
    dynamic_status: int | None = None
    dynamic_media: int = 0
    dynamic_xhr: int = 0
    dynamic_added_media: int = 0
    error: str | None = None


def run_one(name: str, url: str) -> ProbeResult:
    result = ProbeResult(name=name, url=url)
    http_urls: set[str] = set()
    try:
        http = _http(url)
        result.http_status = http.status
        result.http_media = len(http.media)
        http_urls = {item["url"] for item in http.media}
    except Exception as exc:
        result.error = f"http:{type(exc).__name__}:{exc}"

    try:
        dynamic = _dynamic(url, r".*", 2500)
        result.dynamic_status = dynamic.status
        result.dynamic_media = len(dynamic.media)
        result.dynamic_xhr = dynamic.xhr_count
        dynamic_urls = {item["url"] for item in dynamic.media}
        result.dynamic_added_media = len(dynamic_urls - http_urls)
    except Exception as exc:
        suffix = f"dynamic:{type(exc).__name__}:{exc}"
        result.error = f"{result.error}; {suffix}" if result.error else suffix
    return result


def main() -> int:
    rows = [run_one(name, url) for name, url in TARGETS]
    print(json.dumps([asdict(row) for row in rows], ensure_ascii=False, indent=2))

    reachable = [row for row in rows if row.http_status or row.dynamic_status]
    if not reachable:
        print("No target was reachable", file=sys.stderr)
        return 2

    useful = [row for row in rows if row.dynamic_xhr > 0 or row.dynamic_added_media > 0]
    print(f"reachable={len(reachable)}/{len(rows)} dynamic_useful={len(useful)}/{len(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
