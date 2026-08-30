from track_traversal import numbered_track_parts, numbered_track_url


def test_numbered_track_parts():
    assert numbered_track_parts("https://cdn.example/book/0.mp3") == (
        "https://cdn.example/book/",
        0,
        ".mp3",
    )


def test_numbered_track_url_preserves_extension():
    assert numbered_track_url("https://cdn.example/book/7.m4b", 8) == "https://cdn.example/book/8.m4b"


def test_numbered_track_url_rejects_non_numbered_filename():
    assert numbered_track_url("https://cdn.example/book/chapter-one.mp3", 2) is None
