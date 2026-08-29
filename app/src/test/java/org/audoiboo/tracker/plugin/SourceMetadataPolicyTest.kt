package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceMetadataPolicyTest {
    @Test
    fun remoteIdWinsOverUrl() {
        assertEquals(
            "book-42",
            SourceKeys.remoteKey(" book-42 ", "https://example.org/book/42/")
        )
    }

    @Test
    fun urlFallbackIsStableAcrossTrailingSlash() {
        val a = SourceKeys.remoteKey(null, "https://example.org/book/42/")
        val b = SourceKeys.remoteKey(null, "https://example.org/book/42")
        assertEquals(a, b)
    }

    @Test
    fun sourceIsPartOfBookSourceIdentity() {
        val remote = SourceKeys.remoteKey(null, "https://example.org/book/42")
        assertNotEquals(
            SourceKeys.bookSourceKey("source-a", remote),
            SourceKeys.bookSourceKey("source-b", remote)
        )
    }
}
