package org.audoiboo.tracker.plugin.flibusta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlibustaParsersTest {

    @Test
    fun registrySelectsAllThreeProviders() {
        assertEquals(FlibustaVariant.SITE, FlibustaParserRegistry.forUrl("https://flibusta.site/b/535670")?.variant)
        assertEquals(FlibustaVariant.ONE, FlibustaParserRegistry.forUrl("https://flibusta.one/books/49475-korm/")?.variant)
        assertEquals(FlibustaVariant.NAME, FlibustaParserRegistry.forUrl("https://flibusta.name/books/80042-book/")?.variant)
        assertNull(FlibustaParserRegistry.forUrl("https://example.org/book/1"))
    }

    @Test
    fun siteParsesClassicBookAndDirectFb2Link() {
        val html = """
            <html><head>
              <meta property="og:title" content="Тестовая книга - Флибуста">
              <meta property="og:image" content="/covers/535670.jpg">
              <link rel="canonical" href="https://flibusta.site/b/535670">
            </head><body>
              <a href="/a/37193">Тестовый Автор</a>
              <a href="/s/12345">Тестовая серия</a>
              <p>скачать: <a href="/b/535670/fb2">(fb2)</a> - <a href="/b/535670/epub">(epub)</a></p>
            </body></html>
        """.trimIndent()

        val parsed = FlibustaSiteParser.parseBookPage(html, "https://flibusta.site/b/535670")

        assertEquals("535670", parsed.remoteId)
        assertEquals("Тестовая книга", parsed.title)
        assertEquals("Тестовый Автор", parsed.author)
        assertEquals("Тестовая серия", parsed.series)
        assertEquals("https://flibusta.site/covers/535670.jpg", parsed.coverUrl)
        assertEquals(0, parsed.waitSeconds)
        assertTrue(parsed.downloads.any { it.format == FlibustaFormat.FB2 && it.url.endsWith("/b/535670/fb2") })
        assertTrue(parsed.downloads.any { it.format == FlibustaFormat.EPUB })
    }

    @Test
    fun oneParsesFiveSecondCountdownReaderAndRejectsStoreAd() {
        val html = """
            <html><head>
              <meta property="og:title" content="Корм — Флибуста">
              <link rel="canonical" href="https://flibusta.one/books/49475-korm/">
            </head><body>
              <h1>Корм</h1>
              <a href="/authors-books/47846-artem-kamenistyj/">Артем Каменистый</a>
              <div><a href="/books-series/51964-seriya/">Похождения Карата</a> #1</div>
              <a href="/books/49475-korm/reading/">Читать онлайн</a>
              <div>Подготовка к скачиванию файла формата *.fb2. Время ожидания: 5 сек.</div>
              <a href="/download/49475-korm.fb2">Скачать fb2</a>
              <a href="https://www.litres.ru/fake.epub">EPUB</a>
            </body></html>
        """.trimIndent()

        val parsed = FlibustaOneParser.parseBookPage(html, "https://flibusta.one/books/49475-korm/")

        assertEquals("49475", parsed.remoteId)
        assertEquals("Корм", parsed.title)
        assertEquals("Артем Каменистый", parsed.author)
        assertEquals("Похождения Карата", parsed.series)
        assertEquals(1, parsed.seriesNumber)
        assertEquals(5, parsed.waitSeconds)
        assertEquals("https://flibusta.one/books/49475-korm/reading/", parsed.readerUrl)
        assertTrue(parsed.downloads.any { it.format == FlibustaFormat.FB2 && it.waitSeconds == 5 })
        assertFalse(parsed.downloads.any { it.url.contains("litres.ru") })
    }

    @Test
    fun nameParsesTenSecondCountdownAndViewer() {
        val html = """
            <html><head>
              <meta property="og:title" content="Неоновые слезы Аполлона | Флибуста">
              <meta property="og:url" content="https://flibusta.name/books/80042-neonovye-slezy-apollona/">
            </head><body>
              <a href="/authors/61204-roman-prokofev/">Роман Прокофьев</a>
              <a href="/books-series/47811-zvezdnaya-krov/">Звездная кровь</a>
              <a href="/books/80042-neonovye-slezy-apollona/viewer/">Читать</a>
              <p>Скачивание формата fb2 через 10 сек.</p>
              <a href="/files/80042.fb2.zip">Скачать fb2</a>
            </body></html>
        """.trimIndent()

        val parsed = FlibustaNameParser.parseBookPage(html, "https://flibusta.name/books/80042-neonovye-slezy-apollona/")

        assertEquals("80042", parsed.remoteId)
        assertEquals("Неоновые слезы Аполлона", parsed.title)
        assertEquals("Роман Прокофьев", parsed.author)
        assertEquals("Звездная кровь", parsed.series)
        assertEquals(10, parsed.waitSeconds)
        assertTrue(parsed.readerUrl.orEmpty().contains("/viewer/"))
        assertTrue(parsed.downloads.any { it.format == FlibustaFormat.FB2 && it.waitSeconds == 10 })
    }

    @Test
    fun catalogExtractionKeepsBookIdsAndDoesNotTreatFormatLinksAsBooks() {
        val html = """
            <html><body>
              <div class="book-item">
                <a href="/authors-books/61204-roman-prokofev/">Роман Прокофьев</a>
                <a href="/books-series/47811-zvezdnaya-krov/">Звездная Кровь</a>
                <span>#8</span>
                <a href="/books/61205-zvezdnaya-krov-8-istinnyj/">Звездная кровь 8. Истинный</a>
              </div>
              <a href="/books/61205-zvezdnaya-krov-8-istinnyj/viewer/">viewer</a>
            </body></html>
        """.trimIndent()

        val items = FlibustaNameParser.parseCatalog(html, "https://flibusta.name/authors/61204-roman-prokofev/")

        assertEquals(1, items.size)
        assertEquals("61205", items.single().remoteId)
        assertEquals("Звездная кровь 8. Истинный", items.single().title)
        assertEquals("Роман Прокофьев", items.single().author)
        assertEquals("Звездная Кровь", items.single().series)
        assertEquals(8, items.single().seriesNumber)
    }

    @Test
    fun rawHelpersRecognizeZipStyleFb2HrefAndCountdown() {
        val document = org.jsoup.Jsoup.parse("<body>Скачивание формата fb2 через 10 сек.</body>")
        assertEquals(10, extractWaitSeconds(document))

        val anchor = org.jsoup.Jsoup.parse("<a href='/book.fb2.zip'>Скачать FB2</a>")
            .selectFirst("a")
        assertNotNull(anchor)
        assertEquals(
            FlibustaFormat.FB2,
            formatFrom(anchor!!, "https://flibusta.name/files/book.fb2.zip")
        )
    }
}
