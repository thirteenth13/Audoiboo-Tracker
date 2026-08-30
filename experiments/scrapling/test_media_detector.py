from media_detector import collect_from_text, collect_network_responses, looks_like_media


def test_media_extensions_and_querystrings():
    assert looks_like_media("https://cdn.example/book/ch01.mp3?token=abc")
    assert looks_like_media("https://cdn.example/book/master.m3u8#x")
    assert not looks_like_media("https://example.com/book/123")


def test_content_type_can_identify_extensionless_audio():
    assert looks_like_media("https://cdn.example/stream/123", "audio/mpeg")
    assert looks_like_media("https://cdn.example/hls/123", "application/vnd.apple.mpegurl")


def test_html_detector_deduplicates_urls():
    text = 'a https://cdn.example/a.mp3 b https://cdn.example/a.mp3 c https://cdn.example/book.zip'
    result = collect_from_text(text, "https://site.example/book")
    assert [item.url for item in result] == ["https://cdn.example/a.mp3", "https://cdn.example/book.zip"]


def test_xhr_detector_finds_direct_and_json_embedded_media():
    responses = [
        {
            "url": "https://api.example/player",
            "headers": {"content-type": "application/json"},
            "body": '{"track":"https://cdn.example/chapter-01.m4b"}',
        },
        {
            "url": "https://cdn.example/master.m3u8?sig=1",
            "headers": {"content-type": "application/vnd.apple.mpegurl"},
            "body": "",
        },
    ]
    result = collect_network_responses(responses)
    assert {item.url for item in result} == {
        "https://cdn.example/chapter-01.m4b",
        "https://cdn.example/master.m3u8?sig=1",
    }
