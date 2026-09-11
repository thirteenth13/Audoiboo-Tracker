package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BazaPlaylistPolicyTest {
    private val allowed = setOf("baza-knig.info", "cdn.baza-knig.info")

    @Test
    fun parsesJsonPlaylistInOrder() {
        val body = """[{"file":"https://cdn.baza-knig.info/a/01.mp3"},{"file":"https://cdn.baza-knig.info/a/02.mp3"}]"""
        assertEquals(
            listOf(
                "https://cdn.baza-knig.info/a/01.mp3",
                "https://cdn.baza-knig.info/a/02.mp3"
            ),
            BazaPlaylistPolicy.extract(body, "https://baza-knig.info/book.pl.txt", allowed)
        )
    }

    @Test
    fun parsesPlainTextAndRelativePlaylistInOrder() {
        val body = """
            '/audio/book/01.mp3',
            "https://cdn.baza-knig.info/audio/book/02.mp3",
            ../audio/book/03.mp3
        """.trimIndent()
        assertEquals(
            listOf(
                "https://baza-knig.info/audio/book/01.mp3",
                "https://cdn.baza-knig.info/audio/book/02.mp3",
                "https://baza-knig.info/audio/book/03.mp3"
            ),
            BazaPlaylistPolicy.extract(body, "https://baza-knig.info/playlists/book.pl.txt", allowed)
        )
    }

    @Test
    fun rejectsForeignMediaHosts() {
        val body = """["https://evil.example/01.mp3","https://cdn.baza-knig.info/02.mp3"]"""
        val result = BazaPlaylistPolicy.extract(body, "https://baza-knig.info/book.pl.txt", allowed)
        assertEquals(listOf("https://cdn.baza-knig.info/02.mp3"), result)
        assertTrue(result.none { it.contains("evil.example") })
    }

    @Test
    fun deduplicatesWithoutReordering() {
        val body = """
            https://cdn.baza-knig.info/a/01.mp3
            https://cdn.baza-knig.info/a/01.mp3
            https://cdn.baza-knig.info/a/02.mp3
        """.trimIndent()
        assertEquals(
            listOf(
                "https://cdn.baza-knig.info/a/01.mp3",
                "https://cdn.baza-knig.info/a/02.mp3"
            ),
            BazaPlaylistPolicy.extract(body, "https://baza-knig.info/book.pl.txt", allowed)
        )
    }
}
