from poleknig_fast import extract_file_urls


def test_extract_file_urls_keeps_order_and_dedupes():
    html = '''
    <div data-url="/files/3002142?h=abc"></div>
    <script>const x = "https:\/\/poleknig.com\/files\/3002143?h=def";</script>
    <a href="/files/3002142?h=abc">01</a>
    '''
    assert extract_file_urls(html, "https://poleknig.com/books/212841") == [
        "https://poleknig.com/files/3002142?h=abc",
        "https://poleknig.com/files/3002143?h=def",
    ]


def test_extract_file_urls_handles_entities_and_numeric_player_ids():
    html = '''
    <div data-url="/files/3002144?x=1&amp;y=2"></div>
    <script>window.player = {trackId: 3002145};</script>
    '''
    assert extract_file_urls(html, "https://poleknig.com/books/212841") == [
        "https://poleknig.com/files/3002144?x=1&y=2",
        "https://poleknig.com/files/3002145",
    ]
