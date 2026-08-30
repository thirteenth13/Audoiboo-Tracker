from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import asdict, dataclass, field

from cdp_media_capture import capture as capture_cdp
from knigavuhe_probe import capture as capture_knigavuhe
from poleknig_browser import resolve as resolve_poleknig_browser
from poleknig_fast import resolve as resolve_poleknig_http
from service import _dynamic, _http
from track_traversal import traverse

TARGETS = [
    ("baza-knig", "https://baza-knig.info/audio-115251-zvezdnaja-krov-10-prozrachnye-dorogi-roman-prokofev"),
    ("poleknig", "https://poleknig.com/books/212841"),
    ("lis10book", "https://lis10book.com/audio/dlan-sistemy-kniga-3/"),
    ("knigavuhe", "https://knigavuhe.org/book/igra-kota-kniga-vtoraja/"),
    ("izib", "https://pda.izib.uk/art141591"),
]
CAPTURE_XHR = r"(?i).*(audio|media|player|playlist|stream|track|api|m3u8|mp3|m4b|m4a|aac|opus|ogg|flac|zip|rar).*"
SITE_TIMEOUT_SECONDS = 100
MEDIA_FIRST = {"knigavuhe", "izib"}
CDP_SITES = {"poleknig", "izib"}
KNIGAVUHE_MOBILE_FALLBACK = "https://m.knigavuhe.org/book/igra-kota-kniga-vtoraja/"

@dataclass
class ProbeResult:
    name: str; url: str
    http_status: int | None = None; http_media: int = 0
    dynamic_status: int | None = None; dynamic_media: int = 0; dynamic_xhr: int = 0; dynamic_added_media: int = 0
    traversal_responses: int = 0; traversal_media: int = 0; traversal_added_media: int = 0
    media_urls: list[str] = field(default_factory=list); diagnostics: list[str] = field(default_factory=list)
    timed_out: bool = False; error: str | None = None

def _add_error(result, stage, exc):
    suffix=f"{stage}:{type(exc).__name__}:{exc}"; result.error=f"{result.error}; {suffix}" if result.error else suffix

def _run_cdp(result, name, url, all_urls, label: str | None = None):
    try:
        cdp = capture_cdp(url, name, max_tracks=30)
        result.traversal_responses += cdp.requests
        before=len(all_urls); all_urls.update(cdp.media)
        result.traversal_media=len(all_urls); result.traversal_added_media += len(all_urls)-before
        prefix = f"{label}:" if label else ""
        result.diagnostics.append(f"{prefix}cdp-url={url}")
        result.diagnostics.extend(f"{prefix}{x}" for x in cdp.diagnostics[:30])
        result.diagnostics.extend(f"{prefix}cdp-resolver={u}" for u in cdp.resolvers[:12])
    except Exception as exc: _add_error(result,f"{label or 'cdp'}",exc)

def _run_knigavuhe(result, url, all_urls, label):
    try:
        probe = capture_knigavuhe(url, max_tracks=40)
        result.traversal_responses += probe.requests
        before = len(all_urls)
        all_urls.update(probe.media)
        result.traversal_added_media += len(all_urls) - before
        result.traversal_media = len(all_urls)
        result.diagnostics.append(f"{label}:probe-url={url}")
        result.diagnostics.extend(f"{label}:{x}" for x in probe.diagnostics[:40])
    except Exception as exc:
        _add_error(result, f"knigavuhe-{label}", exc)

def run_one(name, url):
    result=ProbeResult(name=name,url=url); all_urls=set()
    if name == "poleknig":
        _run_cdp(result,name,url,all_urls)
        if not all_urls:
            try:
                p=resolve_poleknig_browser(url,max_tracks=30); result.traversal_responses += len(p.resolver_urls); all_urls.update(p.media)
                result.diagnostics.extend(p.diagnostics[:24]); result.diagnostics.extend(f"resolver={u}" for u in p.resolver_urls[:12])
            except Exception as exc: _add_error(result,"poleknig-browser",exc)
        if not all_urls:
            try:
                p=resolve_poleknig_http(url,limit=40); result.traversal_responses += p.requests; result.diagnostics.append(f"http-discovered={len(p.discovered)}"); result.diagnostics.extend(p.statuses[:12]); all_urls.update(p.media)
            except Exception as exc: _add_error(result,"poleknig-fast",exc)
        result.traversal_media=len(all_urls); result.media_urls=sorted(all_urls); return result

    if name == "knigavuhe":
        _run_knigavuhe(result, url, all_urls, "desktop")
        if not all_urls:
            result.diagnostics.append("desktop-no-book-audio:trying-mobile-fallback")
            _run_knigavuhe(result, KNIGAVUHE_MOBILE_FALLBACK, all_urls, "mobile")
        result.traversal_media = len(all_urls)
        result.media_urls = sorted(all_urls)
        return result
    elif name in CDP_SITES:
        _run_cdp(result,name,url,all_urls)

    if name in MEDIA_FIRST and not all_urls:
        try:
            responses,urls=traverse(url,CAPTURE_XHR,max_steps=28); result.traversal_responses += responses; result.traversal_media=len(urls); result.traversal_added_media += len(urls); all_urls.update(urls)
        except Exception as exc: _add_error(result,"media-first",exc)
    if not all_urls:
        try:
            h=_http(url); result.http_status=h.status; result.http_media=len(h.media); all_urls.update(item["url"] for item in h.media)
        except Exception as exc: _add_error(result,"http",exc)
    if not all_urls:
        try:
            d=_dynamic(url,CAPTURE_XHR,900); result.dynamic_status=d.status; result.dynamic_media=len(d.media); result.dynamic_xhr=d.xhr_count
            urls={item["url"] for item in d.media}; result.dynamic_added_media=len(urls-all_urls); all_urls |= urls
        except Exception as exc: _add_error(result,"dynamic",exc)
    if len(all_urls)<=1 and name not in MEDIA_FIRST and name!="lis10book":
        try:
            responses,urls=traverse(url,CAPTURE_XHR,max_steps=28); result.traversal_responses += responses; result.traversal_media=len(urls); new=set(urls)-all_urls; result.traversal_added_media += len(new); all_urls |= set(urls)
        except Exception as exc: _add_error(result,"traversal",exc)
    all_urls={u for u in all_urls if not u.lower().startswith("blob:")}; result.media_urls=sorted(all_urls); return result

def run_isolated(name,url):
    print(f"START {name}",flush=True); cmd=[sys.executable,__file__,"--worker",name,url]
    try: completed=subprocess.run(cmd,text=True,capture_output=True,timeout=SITE_TIMEOUT_SECONDS,check=False)
    except subprocess.TimeoutExpired:
        print(f"TIMEOUT {name} after {SITE_TIMEOUT_SECONDS}s",flush=True); return ProbeResult(name=name,url=url,timed_out=True,error="site-timeout")
    stdout=completed.stdout.strip()
    if completed.stderr.strip(): print(f"STDERR {name}: {completed.stderr.strip()[-1000:]}",flush=True)
    try: result=ProbeResult(**json.loads(stdout.splitlines()[-1]))
    except Exception as exc: result=ProbeResult(name=name,url=url,error=f"worker-output:{type(exc).__name__}:exit={completed.returncode}")
    print(f"DONE {name} http={result.http_status}/{result.http_media} dynamic={result.dynamic_status}/{result.dynamic_media} traversal={result.traversal_media}/{result.traversal_responses} error={result.error or '-'}",flush=True)
    for x in result.diagnostics: print(f"DIAG {name} {x}",flush=True)
    for u in result.media_urls: print(f"MEDIA {name} {u}",flush=True)
    return result

def worker_main(name,url): print(json.dumps(asdict(run_one(name,url)),ensure_ascii=False)); return 0

def main():
    p=argparse.ArgumentParser(); p.add_argument("--worker",action="store_true"); p.add_argument("name",nargs="?"); p.add_argument("url",nargs="?"); a=p.parse_args()
    if a.worker:
        if not a.name or not a.url: p.error("--worker requires name and url")
        return worker_main(a.name,a.url)
    rows=[run_isolated(n,u) for n,u in TARGETS]; print("SUMMARY"); print(json.dumps([asdict(r) for r in rows],ensure_ascii=False,indent=2))
    useful=[r for r in rows if r.media_urls]; timed=[r.name for r in rows if r.timed_out]; print(f"media_found={len(useful)}/{len(rows)} timeouts={','.join(timed) if timed else '-'}"); return 0 if useful else 2

if __name__ == "__main__": raise SystemExit(main())
