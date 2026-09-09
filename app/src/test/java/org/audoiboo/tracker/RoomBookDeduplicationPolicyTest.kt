package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomBookDeduplicationPolicyTest {
    @Test
    fun mergesDlanAuthorAliasesAcrossProviders() {
        val books = listOf(
            book("lis-2", "Длань системы. Книга 2", "Лаэндэл / Алексей Андриенко", 1),
            book("izib-2", "Длань системы. Книга 2", "Лаэндэл", 1),
            book("pole-2", "Длань системы. Книга 2", "Алексей Лаэндэл", 1),
            book("audio-2", "Лаэндэл - Длань системы 02", "Лаэндэл", 1)
        )
        val result = RoomBookDeduplicationPolicy.deduplicate("Длань системы", books)
        assertEquals(1, result.books.size)
        assertEquals(3, result.duplicateToWinner.size)
        assertTrue(result.books.single().author.orEmpty().contains("Лаэндэл"))
    }

    @Test
    fun mergesAudiobooNumberedIgraKotaWithCanonicalRowsButKeepsPrequel() {
        val books = listOf(
            book("canonical-1", "Игра Кота", "Роман Прокофьев", 0),
            book("canonical-2", "Игра Кота. Книга вторая", "Роман Прокофьев", 1),
            book("audio-1", "Прокофьев Роман - Игра Кота 01", "Прокофьев Роман", 7),
            book("audio-2", "Прокофьев Роман - Игра Кота 02", "Прокофьев Роман", 8),
            book("prequel", "Прокофьев Роман, Стрельцов Александр - Игра Кота 00. Пандорум", "Прокофьев Роман, Стрельцов Александр", 9)
        )
        val result = RoomBookDeduplicationPolicy.deduplicate("Игра Кота", books)
        assertEquals(3, result.books.size)
        assertEquals(2, result.duplicateToWinner.size)
        assertTrue(result.books.any { it.id == "canonical-1" })
        assertTrue(result.books.any { it.id == "canonical-2" })
        assertTrue(result.books.any { it.id == "prequel" })
    }

    @Test
    fun doesNotMergeSameTitleFromForeignAuthor() {
        val books = listOf(book("stellar", "Прометей", "Роман Прокофьев", 8), book("foreign", "Прометей", "Нина Световидова", 8))
        val result = RoomBookDeduplicationPolicy.deduplicate("Стеллар", books)
        assertEquals(2, result.books.size)
        assertTrue(result.duplicateToWinner.isEmpty())
        assertFalse(result.books.map { it.id }.toSet().size == 1)
    }

    @Test
    fun onlyExplicitVolumeZeroIsProtectedAsPrimaryExtra() {
        assertTrue(RoomBookDeduplicationPolicy.isExplicitPrimaryExtra("Игра Кота", "Прокофьев Роман - Игра Кота 00. Пандорум"))
        assertFalse(RoomBookDeduplicationPolicy.isExplicitPrimaryExtra("Звездная Кровь", "Звездная Кровь. Белый Дьявол"))
        assertFalse(RoomBookDeduplicationPolicy.isExplicitPrimaryExtra("Звездная Кровь", "Звездная Кровь 13"))
    }

    @Test
    fun strongFantLabNumberedBackboneDropsNestedSubcycleRows() {
        val main = buildList {
            add(book("main-1", "Звездная Кровь", "Роман Прокофьев", 0))
            for (number in 2..11) {
                add(book("main-$number", "Звездная Кровь-$number. Том $number", "Роман Прокофьев", number - 1))
            }
        }
        val nested = listOf(
            book("nested-node", "Тысяча Братьев", "Роман Прокофьев", 10),
            book("nested-1", "Звездная Кровь. Пламени Подобный", "Роман Прокофьев", 11),
            book("nested-2", "Звездная Кровь. Лёд-Кузнец", "Роман Прокофьев", 12),
            book("nested-3", "Звездная Кровь. Владыка Теней", "Роман Прокофьев", 13),
            book("nested-4", "Звездная Кровь. Дарующий Молнии", "Роман Прокофьев", 14)
        )

        val anchors = RoomBookDeduplicationPolicy.authoritativeFantLabAnchors("Звездная Кровь", main + nested)

        assertEquals(11, anchors.size)
        assertEquals((1..11).map { "main-$it" }.toSet(), anchors.map { it.id }.toSet())
    }

    @Test
    fun unnumberedFantLabSeriesIsNotAggressivelyFiltered() {
        val books = (1..7).map { number ->
            book("book-$number", "Отдельное название $number", "Автор", number - 1)
        }
        assertEquals(books, RoomBookDeduplicationPolicy.authoritativeFantLabAnchors("Серия", books))
    }

    private fun book(id: String, title: String, author: String?, sortIndex: Int) = BookEntity(
        id = id, seriesId = "series", title = title, url = "https://example.org/$id", author = author,
        coverUrl = null, status = "NEW", archiveUrl = null, sortIndex = sortIndex, updatedAt = 1L
    )
}
