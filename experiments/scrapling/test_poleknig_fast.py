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
