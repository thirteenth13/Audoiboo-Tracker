package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnigavuheMediaScopePolicyTest {
    @Test
    fun singleAudiobookIdIsAccepted() {
        val result = KnigavuheMediaScopePolicy.sanitize(
            listOf(
                "https://knigavuhe.org/audio/123/mobile/001.mp3?token=a",
                "https://knigavuhe.org/audio/123/mobile/002.mp3?token=b"
            )
        )
        assertFalse(result.mixedBooks)
        assertEquals(setOf("123"), result.bookIds)
        assertEquals(2, result.mediaUrls.size)
    }

    @Test
    fun severalAudiobookIdsAreRejectedAsOneBook() {
        val result = KnigavuheMediaScopePolicy.sanitize(
            listOf(
                "https://knigavuhe.org/audio/123/mobile/001.mp3",
                "https://knigavuhe.org/audio/456/mobile/001.mp3"
            )
        )
        assertTrue(result.mixedBooks)
        assertEquals(setOf("123", "456"), result.bookIds)
        assertTrue(result.mediaUrls.isEmpty())
    }

    @Test
    fun duplicateSignedUrlsDoNotCreateFalseMixedScope() {
        val result = KnigavuheMediaScopePolicy.sanitize(
            listOf(
                "https://knigavuhe.org/audio/123/mobile/001.mp3?token=a",
                "https://knigavuhe.org/audio/123/mobile/001.mp3?token=b"
            )
        )
        assertFalse(result.mixedBooks)
        assertEquals(setOf("123"), result.bookIds)
    }

    @Test
    fun urlWithoutAudioScopeDoesNotInventBookId() {
        assertEquals(null, KnigavuheMediaScopePolicy.bookId("https://knigavuhe.org/assets/player.js"))
    }
}
