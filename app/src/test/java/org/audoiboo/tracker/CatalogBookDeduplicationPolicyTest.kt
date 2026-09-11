package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogBookDeduplicationPolicyTest {
    @Test
    fun providerRowsNeverBecomeCatalogAnchors() {
        val catalog = book("catalog-1", "catalog://fantlab/book/100", "Серия", 0)
        val provider = book("audio-1", "https://audio.example/book/1", "Серия", 0)

        val anchors = CatalogBookDeduplicationPolicy.anchors(
            "catalog://fantlab/series/fantlab%3A1%3Aseries",
            "Серия",
            listOf(catalog, provider)
        )

        assertEquals(listOf("catalog-1"), anchors.map { it.id })
        assertTrue(CatalogBookDeduplicationPolicy.isCanonicalCatalogBook(catalog))
        assertFalse(CatalogBookDeduplicationPolicy.isCanonicalCatalogBook(provider))
    }

    @Test
    fun fantlabCatalogUsesAuthoritativeNumberedBackbone() {
        val main = buildList {
            add(book("main-1", "catalog://fantlab/book/1", "Звездная Кровь", 0))
            for (number in 2..11) {
                add(book("main-$number", "catalog://fantlab/book/$number", "Звездная Кровь-$number. Том $number", number - 1))
            }
        }
        val nested = listOf(
            book("nested-node", "catalog://fantlab/book/200", "Тысяча Братьев", 10),
            book("nested-1", "catalog://fantlab/book/201", "Звездная Кровь. Пламени Подобный", 11),
            book("nested-2", "catalog://fantlab/book/202", "Звездная Кровь. Лёд-Кузнец", 12)
        )

        val anchors = CatalogBookDeduplicationPolicy.anchors(
            "catalog://fantlab/series/fantlab%3A82803%3A%D0%B7%D0%B2%D0%B5%D0%B7%D0%B4%D0%BD%D0%B0%D1%8F",
            "Звездная Кровь",
            main + nested
        )

        assertEquals((1..11).map { "main-$it" }.toSet(), anchors.map { it.id }.toSet())
    }

    @Test
    fun nonFantlabCatalogKeepsAllCanonicalBooksAsAnchors() {
        val books = (1..7).map { number ->
            book("book-$number", "catalog://openlibrary/book/$number", "Отдельное название $number", number - 1)
        }

        val anchors = CatalogBookDeduplicationPolicy.anchors(
            "catalog://openlibrary/series/openlibrary%3Aseries",
            "Серия",
            books
        )

        assertEquals(books.map { it.id }, anchors.map { it.id })
        assertEquals("openlibrary", CatalogBookDeduplicationPolicy.catalogProviderId("catalog://openlibrary/series/x"))
    }

    private fun book(id: String, url: String, title: String, sortIndex: Int) = BookEntity(
        id = id,
        seriesId = "catalog::fantlab:1:series",
        title = title,
        url = url,
        author = "Роман Прокофьев",
        coverUrl = null,
        status = "NEW",
        archiveUrl = null,
        sortIndex = sortIndex,
        updatedAt = 1L
    )
}
