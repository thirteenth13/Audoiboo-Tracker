package org.audoiboo.tracker

import org.audoiboo.tracker.plugin.audiobooAuthorAliases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobooSearchParserTest {
    @Test
    fun parsesDleStyleSearchCards() {
        val html = """
            <html><body>
              <div class="shortstory">
                <h2 class="title"><a href="/books/nedotyopa/">Недотёпа</a></h2>
                <div>Автор: <a href="/xfsearch/avtora/Сергей%20Лукьяненко/">Сергей Лукьяненко</a></div>
                <div>Серия: <a href="/xfsearch/cikl/Трикс/">Трикс</a></div>
                <img data-src="/uploads/nedotyopa.jpg" />
              </div>
            </body></html>
        """.trimIndent()

        val books = AudiobooSearchParser.parse(html, "https://audioboo.org/?do=search")

        assertEquals(1, books.size)
        assertEquals("Недотёпа", books.single().title)
        assertEquals("https://audioboo.org/books/nedotyopa/", books.single().url)
        assertEquals("Сергей Лукьяненко", books.single().author)
        assertEquals("Трикс", books.single().seriesTitle)
        assertEquals("https://audioboo.org/uploads/nedotyopa.jpg", books.single().coverUrl)
    }

    @Test
    fun ignoresNavigationAndSearchLinks() {
        val html = """
            <html><body>
              <div class="item"><h2><a href="/?do=search&story=test">Поиск</a></h2></div>
              <div class="item"><h2><a href="/xfsearch/cikl/Трикс/">Трикс</a></h2></div>
              <div class="item"><h2><a href="/page/2/">2</a></h2></div>
            </body></html>
        """.trimIndent()

        assertTrue(AudiobooSearchParser.parse(html, "https://audioboo.org/").isEmpty())
    }

    @Test
    fun fallsBackToHeadingLinksWithoutKnownCardClass() {
        val html = """
            <html><body>
              <section>
                <h3><a href="https://audioboo.org/audio-123-nedotyopa.html">Недотёпа</a></h3>
                <p>Автор: Сергей Лукьяненко</p>
              </section>
            </body></html>
        """.trimIndent()

        val books = AudiobooSearchParser.parse(html, "https://audioboo.org/")
        assertEquals(1, books.size)
        assertEquals("Недотёпа", books.single().title)
    }

    @Test
    fun authorFallbackTriesOriginalAndSurnameFirstAliases() {
        assertEquals(
            listOf("Сергей Тармашев", "Тармашев Сергей"),
            audiobooAuthorAliases("  Сергей   Тармашев  ")
        )
        assertEquals(listOf("Пелевин"), audiobooAuthorAliases("Пелевин"))
    }
}
