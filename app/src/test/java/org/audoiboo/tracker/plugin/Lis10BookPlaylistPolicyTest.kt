package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class Lis10BookPlaylistPolicyTest {
    @Test
    fun resolvesRelativeChaptersAndPreservesPositionOrder() {
        val json = """
            {
              "chapters": [
                {"position": 2, "src": "/media/book/chapter-02.mp3?token=b"},
                {"position": 1, "src": "https://cdn.lis10book.com/media/book/chapter-01.mp3?token=a"}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf(
                "https://cdn.lis10book.com/media/book/chapter-01.mp3?token=a",
                "https://lis10book.com/media/book/chapter-02.mp3?token=b"
            ),
            Lis10BookPlaylistPolicy.extract(
                responseBody = json,
                baseUrl = "https://lis10book.com/api/p/dlan-sistemy-kniga-3",
                allowedHosts = setOf("lis10book.com")
            )
        )
    }

    @Test
    fun allowsSubdomainsButRejectsForeignAndNonAudioUrls() {
        val json = """
            {
              "chapters": [
                {"position": 1, "src": "//cdn.lis10book.com/book/01.m4a"},
                {"position": 2, "src": "https://evil.example/book/02.mp3"},
                {"position": 3, "src": "/api/metadata/3"}
              ]
            }
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.lis10book.com/book/01.m4a"),
            Lis10BookPlaylistPolicy.extract(
                responseBody = json,
                baseUrl = "https://lis10book.com/api/p/example",
                allowedHosts = setOf("lis10book.com")
            )
        )
    }
}
