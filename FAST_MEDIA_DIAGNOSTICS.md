# Fast media diagnostics

Plugin Diagnostics now keeps full series structure checks while limiting expensive media work:

- list every series book URL/title;
- run `bookLookup` only for the first two entries;
- run one real WebView `mediaCapture` on the first valid book;
- if capture returns zero media, run one raw probe on that same book.

The raw probe records candidate source (`network`, DOM, HTML, performance, XHR/fetch, media src), host, extension, plugin-filter result, activation count/labels, and load/first-candidate/total timings. The report emits a compact `verdict` such as `FOUND`, `FOUND_TRACK_MISMATCH`, `RAW_FOUND_BUT_FILTERED`, `RAW_ACCEPTED_BUT_CAPTURE_MISSED`, `PLAYER_NOT_ACTIVATED_OR_NO_TRACKS`, or `NO_RAW_MEDIA`.
