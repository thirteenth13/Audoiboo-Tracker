# Baza-Knig source plugin

First real external `.abplugin` implementation for Audoiboo Tracker.

Supported hosts:
- `baza-knig.info` (current streaming/library site)
- `baza-knig.top` (legacy/current mirror indexed by search engines)

Supported operations:
- `seriesLookup` — accepts either a series page or a book page. On a book page it follows the site's series/cycle link and then enumerates books.
- `bookLookup` — extracts title, author, cycle name, cover and description.
- `downloadResolution` — best-effort extraction of direct MP3 links exposed as anchors in the returned HTML. Dynamic player-only audio remains intentionally unsupported by the declarative runtime and falls back to the browser.

The package contains only JSON rules. It does not execute JavaScript, Dex/Jar/APK/native code, access Android APIs, or open network connections directly.

Build output should be named `baza-knig-1.abplugin` with `plugin.json` at ZIP root and the `rules/` directory beside it.
