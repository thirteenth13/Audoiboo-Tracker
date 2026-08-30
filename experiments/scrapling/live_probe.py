from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import asdict, dataclass, field

from poleknig_browser import resolve as resolve_poleknig_browser
from poleknig_fast import resolve as resolve_poleknig_http
from service import _dynamic, _http
from track_traversal import traverse


TARGETS = [
    ("baza-knig", "https://baza-knig.info/audio-115251-zvezdnaja-krov-10-prozrachnye-dorogi-roman-prokofev"),
    ("poleknig", "https://poleknig.com/books/212841"),
    ("lis10book", "https://lis10book.com/audio/dlan-sistemy-kniga-3/"),
    ("knigavuhe", "https://m.knigavuhe.org/book/igra-kota-kniga-vtoraja/"),
    ("izib", "https://pda.izib.uk/art141591"),
]

CAPTURE_XHR = r"(?i).*(audio|media|player|playlist|stream|track|api|m3u8|mp3|m4b|m4a|aac|opus|ogg|flac|zip|rar).*"
SITE_TIMEOUT_SECONDS = 100
MEDIA_FIRST = {"knigavuhe", "izib"}


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
    traversal_responses: int = 0
    traversal_media: int = 0
    traversal_added_media: int = 0
    media_urls: list[str] = field(default_factory=list)
    diagnostics: list[str] = field(default_factory=list)
    timed_out: bool = False
    error: str | None = None


def _add_error(result: ProbeResult, stage: str, exc: Exception) -> None:
    suffix = f"{stage}:{type(exc).__name__}:{exc}"
    result.error = f"{result.error}; {suffix}" if result.error else suffix


def run_one(name: str, url: str) -> ProbeResult:
    result = ProbeResult(name=name, url=url)
    all_urls: set[str] = set()

    if name == "poleknig":
        try:
            browser_probe = resolve_poleknig_browser(url, max_tracks=60)
            result.traversal_responses = len(browser_probe.resolver_urls)
            result.traversal_media = len(browser_probe.media)
            result.traversal_added_media = len(browser_probe.media)
            all_urls.update(browser_probe.media)
            result.diagnostics.extend(browser_probe.diagnostics[:24])
            result.diagnostics.extend(f"resolver={u}" for u in browser_probe.resolver_urls[:12])
        except Exception as exc:
            _add_error(result, "poleknig-browser", exc)

        # Keep the cheap HTTP resolver as a fallback/diagnostic only.
        if not all_urls:
            try:
                http_probe = resolve_poleknig_http(url, limit=40)
                result.traversal_responses += http_probe.requests
                result.diagnostics.append(f"http-discovered={len(http_probe.discovered)}")
                result.diagnostics.extend(http_probe.statuses[:12])
                all_urls.update(http_probe.media)
                result.traversal_media = len(all_urls)
                result.traversal_added_media = len(all_urls)
            except Exception as exc:
                _add_error(result, "poleknig-fast", exc)

        result.media_urls = sorted(all_urls)
        return result

    if name in MEDIA_FIRST:
        try:
            responses, urls = traverse(url, CAPTURE_XHR, max_steps=28)
            result.traversal_responses = responses
            result.traversal_media = len(urls)
            result.traversal_added_media = len(urls)
            all_urls.update(urls)
        except Exception as exc:
            _add_error(result, "media-first", exc)

    if not all_urls:
        try:
            http = _http(url)
            result.http_status = http.status
            result.http_media = len(http.media)
            all_urls.update(item["url"] for item in http.media)
        except Exception as exc:
            _add_error(result, "http", exc)

    if not all_urls:
        try:
            dynamic = _dynamic(url, CAPTURE_XHR, 900)
            result.dynamic_status = dynamic.status
            result.dynamic_media = len(dynamic.media)
            result.dynamic_xhr = dynamic.xhr_count
            dynamic_urls = {item["url"] for item in dynamic.media}
            result.dynamic_added_media = len(dynamic_urls - all_urls)
            all_urls |= dynamic_urls
        except Exception as exc:
            _add_error(result, "dynamic", exc)

    if len(all_urls) <= 1 and name not in MEDIA_FIRST and name != "lis10book":
        try:
            responses, urls = traverse(url, CAPTURE_XHR, max_steps=28)
            result.traversal_responses = responses
            result.traversal_media = len(urls)
            new_urls = set(urls) - all_urls
            result.traversal_added_media = len(new_urls)
            all_urls |= set(urls)
        except Exception as exc:
            _add_error(result, "traversal", exc)

    all_urls = {u for u in all_urls if not u.lower().startswith("blob:")}
    result.media_urls = sorted(all_urls)
    return result


def run_isolated(name: str, url: str) -> ProbeResult:
    print(f"START {name}", flush=True)
    cmd = [sys.executable, __file__, "--worker", name, url]
    try:
        completed = subprocess.run(cmd, text=True, capture_output=True, timeout=SITE_TIMEOUT_SECONDS, check=False)
    except subprocess.TimeoutExpired:
        print(f"TIMEOUT {name} after {SITE_TIMEOUT_SECONDS}s", flush=True)
        return ProbeResult(name=name, url=url, timed_out=True, error="site-timeout")

    stdout = completed.stdout.strip()
    if completed.stderr.strip():
        print(f"STDERR {name}: {completed.stderr.strip()[-1000:]}", flush=True)

    try:
        result = ProbeResult(**json.loads(stdout.splitlines()[-1]))
    except Exception as exc:
        result = ProbeResult(name=name, url=url, error=f"worker-output:{type(exc).__name__}:exit={completed.returncode}")

    print(
        f"DONE {name} http={result.http_status}/{result.http_media} "
        f"dynamic={result.dynamic_status}/{result.dynamic_media} "
        f"traversal={result.traversal_media}/{result.traversal_responses} "
        f"error={result.error or '-'}",
        flush=True,
    )
    for diagnostic in result.diagnostics:
        print(f"DIAG {name} {diagnostic}", flush=True)
    for media_url in result.media_urls:
        print(f"MEDIA {name} {media_url}", flush=True)
    return result


def worker_main(name: str, url: str) -> int:
    print(json.dumps(asdict(run_one(name, url)), ensure_ascii=False))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker", action="store_true")
    parser.add_argument("name", nargs="?")
    parser.add_argument("url", nargs="?")
    args = parser.parse_args()

    if args.worker:
        if not args.name or not args.url:
            parser.error("--worker requires name and url")
        return worker_main(args.name, args.url)

    rows = [run_isolated(name, url) for name, url in TARGETS]
    print("SUMMARY")
    print(json.dumps([asdict(row) for row in rows], ensure_ascii=False, indent=2))
    useful = [row for row in rows if row.media_urls]
    timed_out = [row.name for row in rows if row.timed_out]
    print(f"media_found={len(useful)}/{len(rows)} timeouts={','.join(timed_out) if timed_out else '-'}")
    return 0 if useful else 2


if __name__ == "__main__":
    raise SystemExit(main())
