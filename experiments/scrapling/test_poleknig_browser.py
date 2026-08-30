from poleknig_browser import redirect_target


def test_redirect_target_extracts_storage_mp3():
    assert redirect_target(
        "https://poleknig.com/files/3002142?h=abc",
        302,
        {"Location": "https://s15.poleknig.com/storage/9b/1a/test.mp3"},
    ) == "https://s15.poleknig.com/storage/9b/1a/test.mp3"


def test_redirect_target_rejects_non_audio_and_non_resolver():
    assert redirect_target(
        "https://poleknig.com/files/3002142?h=abc",
        302,
        {"location": "/books/212841"},
    ) is None
    assert redirect_target(
        "https://poleknig.com/books/212841",
        302,
        {"location": "https://s15.poleknig.com/storage/test.mp3"},
    ) is None
