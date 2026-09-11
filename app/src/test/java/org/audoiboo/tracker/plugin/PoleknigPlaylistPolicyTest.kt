package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PoleknigPlaylistPolicyTest {
    @Test
    fun parsesLegacyArrayObjectsInOrder() {
        val raw = """[
            {"title":"1","file":"/files/10001"},
            {"title":"2","file":"https://poleknig.com/files/10002"}
        ]""".trimIndent()

        assertEquals(
            listOf(
                "https://poleknig.com/files/10001",
                "https://poleknig.com/files/10002"
            ),
            PoleknigPlaylistPolicy.extract(raw, "https://poleknig.com/books/212841")
        )
    }

    @Test
    fun parsesWrappedPlaylistAndAlternateKeys() {
        val raw = """{"playlist":[
            {"src":"/files/20001"},
            {"url":"/files/20002"}
        ]}"""

        assertEquals(
            listOf(
                "https://poleknig.com/files/20001",
                "https://poleknig.com/files/20002"
            ),
            PoleknigPlaylistPolicy.extract(raw, "https://poleknig.com/books/9")
        )
    }

    @Test
    fun parsesStringEntriesAndKeepsFirstOrVariant() {
        val raw = """[
            "/files/30001 or /files/99999",
            "https://poleknig.com/files/30002"
        ]"""

        assertEquals(
            listOf(
                "https://poleknig.com/files/30001",
                "https://poleknig.com/files/30002"
            ),
            PoleknigPlaylistPolicy.extract(raw, "https://poleknig.com/books/10")
        )
    }

    @Test
    fun dedupesWithoutChangingPlaylistOrder() {
        val raw = """{"tracks":["/files/4","/files/3","/files/4"]}"""
        assertEquals(
            listOf("https://poleknig.com/files/4", "https://poleknig.com/files/3"),
            PoleknigPlaylistPolicy.extract(raw, "https://poleknig.com/books/11")
        )
    }
}
