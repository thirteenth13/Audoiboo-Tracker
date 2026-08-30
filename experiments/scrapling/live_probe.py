from __future__ import annotations

import argparse
import json
import subprocess
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

# Capture likely player/media/API traffic instead of every advertising XHR.
CAPTURE_XHR = r"(?i).*(audio|media|player|playlist|stream|track|api|m3u8|mp3|m4b|m4a|aac|opus|ogg|flac|zip|rar).*"
SITE_TIMEOUT_SECONDS = 75


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
    timed_out: bool = False
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
        dynamic = _dynamic(url, CAPTURE_XHR, 1500)
        result.dynamic_status = dynamic.status
        result.dynamic_media = len(dynamic.media)
        result.dynamic_xhr = dynamic.xhr_count
        dynamic_urls = {item["url"] for item in dynamic.media}
        result.dynamic_added_media = len(dynamic_urls - http_urls)
    except Exception as exc:
        suffix = f"dynamic:{type(exc).__name__}:{exc}"
        result.error = f"{result.error}; {suffix}" if result.error else suffix
    return result


def run_isolated(name: str, url: str) -> ProbeResult:
    print(f"START {name}", flush=True)
    cmd = [sys.executable, __file__, "--worker", name, url]
    try:
        completed = subprocess.run(
            cmd,
            text=True,
            capture_output=True,
            timeout=SITE_TIMEOUT_SECONDS,
            check=False,
        )
    except subprocess.TimeoutExpired:
        print(f"TIMEOUT {name} after {SITE_TIMEOUT_SECONDS}s", flush=True)
        return ProbeResult(name=name, url=url, timed_out=True, error="site-timeout")

    stdout = completed.stdout.strip()
    if completed.stderr.strip():
        print(f"STDERR {name}: {completed.stderr.strip()[-1200:]}", flush=True)

    try:
        payload = json.loads(stdout.splitlines()[-1])
        result = ProbeResult(**payload)
    except Exception as exc:
        result = ProbeResult(
            name=name,
            url=url,
            error=f"worker-output:{type(exc).__name__}:exit={completed.returncode}",
        )

    print(
        f"DONE {name} http={result.http_status}/{result.http_media} "
        f"dynamic={result.dynamic_status}/{result.dynamic_media} "
        f"xhr={result.dynamic_xhr} added={result.dynamic_added_media} "
        f"error={result.error or '-'}",
        flush=True,
    )
    return result


def worker_main(name: str, url: str) -> int:
    result = run_one(name, url)
    print(json.dumps(asdict(result), ensure_ascii=False))
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

    reachable = [row for row in rows if row.http_status or row.dynamic_status]
    if not reachable:
        print("No target was reachable", file=sys.stderr)
        return 2

    useful = [row for row in rows if row.dynamic_xhr > 0 or row.dynamic_added_media > 0]
    timed_out = [row.name for row in rows if row.timed_out]
    print(
        f"reachable={len(reachable)}/{len(rows)} "
        f"dynamic_useful={len(useful)}/{len(rows)} "
        f"timeouts={','.join(timed_out) if timed_out else '-'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
