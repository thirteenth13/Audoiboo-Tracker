# Scrapling parser-backend experiment

This directory is intentionally isolated from the Android application. It tests whether Audioboo plugins can delegate difficult pages to an optional remote parser service without making the APK depend on Python or Chromium.

## Prototype flow

`auto` mode tries plain HTTP first, then Chromium, then stealth Chromium. Media candidates are collected from HTML, direct network responses and XHR/fetch response bodies. Current candidate types: MP3, M4B, M4A, AAC, OGG, OPUS, FLAC, M3U8, ZIP and RAR.

## Run locally

```bash
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
uvicorn service:app --host 0.0.0.0 --port 8765
```

Browser fetchers may require the browser-install step documented by Scrapling for the installed version.

Example request:

```bash
curl -X POST http://127.0.0.1:8765/parse \
  -H 'content-type: application/json' \
  -d '{"url":"https://example.com/book","mode":"auto","capture_xhr":".*"}'
```

This branch is experimental only. Do not merge until real-site tests confirm that the service adds value over the existing plugin HTTP runtime.
