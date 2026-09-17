package org.audoiboo.tracker.plugin.flibusta

import org.junit.Assert.*
import org.junit.Test

class FlibustaDownloadResolverTest {
    @Test fun classifierRecognizesZipRawFb2AndHtml() {
        assertEquals(FlibustaPayloadKind.ZIP_FB2, FlibustaPayloadClassifier.classify(byteArrayOf(0x50,0x4b,0x03,0x04)))
        assertEquals(FlibustaPayloadKind.RAW_FB2, FlibustaPayloadClassifier.classify("<?xml version=\"1.0\"?><FictionBook/>".toByteArray()))
        assertEquals(FlibustaPayloadKind.HTML, FlibustaPayloadClassifier.classify("<html>Error</html>".toByteArray()))
    }

    @Test fun siteUsesDirectFb2AndReturnsZip() {
        val fake = FakeTransport(mutableListOf(
            html(200, "https://flibusta.site/b/535670", "<html><h1>Book</h1></html>"),
            zip("https://flibusta.site/b/535670/fb2")
        ))
        val result = resolver(fake).resolveFb2("https://flibusta.site/b/535670")
        assertTrue(result is FlibustaResolveResult.Success)
        assertEquals("https://flibusta.site/b/535670/fb2", fake.urls.last())
    }

    @Test fun retriesTransient502BeforeSuccess() {
        val fake = FakeTransport(mutableListOf(
            html(200, "https://flibusta.site/b/1", "<html><a href='/b/1/fb2'>fb2</a></html>"),
            FlibustaHttpResponse(502, "https://flibusta.site/b/1/fb2"),
            zip("https://flibusta.site/b/1/fb2")
        ))
        val sleeps = mutableListOf<Long>()
        val result = resolver(fake, sleeps).resolveFb2("https://flibusta.site/b/1")
        assertTrue(result is FlibustaResolveResult.Success)
        assertTrue(sleeps.contains(2_000L))
    }

    @Test fun oneKeepsCookieAndHonorsFiveSecondWait() {
        val page = """<html><body>Время ожидания: 5 сек.<a href='/download/book.fb2'>Скачать fb2</a></body></html>"""
        val fake = FakeTransport(mutableListOf(
            FlibustaHttpResponse(200, "https://flibusta.one/books/49475-korm/", mapOf("Set-Cookie" to listOf("SESS=abc; Path=/")), page.toByteArray()),
            zip("https://flibusta.one/download/book.fb2")
        ))
        val sleeps = mutableListOf<Long>()
        val result = resolver(fake, sleeps).resolveFb2("https://flibusta.one/books/49475-korm/")
        assertTrue(result is FlibustaResolveResult.Success)
        assertTrue(sleeps.contains(5_000L))
        assertTrue(fake.headers.last()["Cookie"]?.contains("SESS=abc") == true)
    }

    @Test fun nameHonorsTenSecondWait() {
        val page = """<html><body>Скачивание формата fb2 через 10 сек.<a href='/get/a.fb2'>Скачать fb2</a></body></html>"""
        val fake = FakeTransport(mutableListOf(
            html(200, "https://flibusta.name/books/80042-test/", page),
            zip("https://flibusta.name/get/a.fb2")
        ))
        val sleeps = mutableListOf<Long>()
        assertTrue(resolver(fake, sleeps).resolveFb2("https://flibusta.name/books/80042-test/") is FlibustaResolveResult.Success)
        assertTrue(sleeps.contains(10_000L))
    }

    @Test fun followsHtmlInterstitialWithoutJavascript() {
        val first = """<html><a href='/prepare.fb2'>fb2</a></html>"""
        val second = """<html><body>через 1 сек<a href='/files/final.fb2'>Скачать fb2</a></body></html>"""
        val fake = FakeTransport(mutableListOf(
            html(200, "https://flibusta.one/books/7-x/", first),
            html(200, "https://flibusta.one/prepare.fb2", second),
            zip("https://flibusta.one/files/final.fb2")
        ))
        val sleeps = mutableListOf<Long>()
        val result = resolver(fake, sleeps).resolveFb2("https://flibusta.one/books/7-x/")
        assertTrue(result is FlibustaResolveResult.Success)
        assertTrue(sleeps.contains(1_000L))
    }

    @Test fun html200WithoutFb2IsRejected() {
        val fake = FakeTransport(mutableListOf(html(200, "https://flibusta.one/books/7-x/", "<html>No download</html>")))
        val result = resolver(fake).resolveFb2("https://flibusta.one/books/7-x/")
        assertEquals(FlibustaFailureCode.NO_FB2_LINK, (result as FlibustaResolveResult.Failure).code)
    }

    private fun resolver(fake: FakeTransport, sleeps: MutableList<Long> = mutableListOf()) =
        FlibustaDownloadResolver(fake, FlibustaSleeper { sleeps += it }, FlibustaRetryPolicy())

    private fun html(code: Int, url: String, value: String) =
        FlibustaHttpResponse(code, url, mapOf("Content-Type" to listOf("text/html; charset=utf-8")), value.toByteArray())

    private fun zip(url: String) = FlibustaHttpResponse(
        200, url, mapOf("Content-Type" to listOf("application/fb2+zip")),
        byteArrayOf(0x50,0x4b,0x03,0x04,1,2,3)
    )

    private class FakeTransport(private val responses: MutableList<FlibustaHttpResponse>) : FlibustaTransport {
        val urls = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()
        override fun get(url: String, headers: Map<String, String>): FlibustaHttpResponse {
            urls += url
            this.headers += headers.toMap()
            check(responses.isNotEmpty()) { "Unexpected request: $url" }
            return responses.removeAt(0)
        }
    }
}
