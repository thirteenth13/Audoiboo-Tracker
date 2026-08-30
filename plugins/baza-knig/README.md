# Baza-Knig source plugin

First real external `.abplugin` implementation for Audoiboo Tracker.

Supported hosts:
- `baza-knig.info` (current streaming/library site)
- `baza-knig.top` (legacy/current mirror indexed by search engines)

Supported operations:
- `seriesSearch` — searches Baza-Knig for likely books/series by canonical series title; candidates are then hydrated through `seriesLookup` and scored by the central cross-source matcher.
- `seriesLookup` — accepts either a series page or a book page. On a book page it follows the site's series/cycle link and then enumerates books.
- `bookLookup` — extracts title, author, cycle name, cover and description.
- `downloadResolution` — best-effort extraction of direct MP3 links exposed as anchors in the returned HTML. Dynamic player-only audio remains intentionally unsupported by the declarative runtime and falls back to the browser.

The package contains only JSON rules. It does not execute JavaScript, Dex/Jar/APK/native code, access Android APIs, or open network connections directly.

The current distributable package is stored at `plugins/packages/baza-knig-2.abplugin` and published through `plugins/catalog.json`. Its catalog SHA-256 is checked before installation/update.
