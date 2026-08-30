package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class PrimarySourceBookRetentionPolicyTest {
    private fun book(id: String, url: String) = BookEntity(
        id = id,
        seriesId = "series",
        title = id,
        url = url,
        author = null,
        coverUrl = null,
        status = "NEW",
        archiveUrl = null
    )

    @Test
    fun removesMissingBooksOwnedByPrimarySource() {
        val existing = listOf(
            book("keep-primary", "https://primary.example/keep"),
            book("remove-primary", "https://primary.example/remove")
        )

        val keep = PrimarySourceBookRetentionPolicy.keepIds(
            existingBooks = existing,
            incomingIds = listOf("keep-primary"),
            ownedByCurrentSource = { it.startsWith("https://primary.example/") }
        )

        assertEquals(listOf("keep-primary"), keep)
    }

    @Test
    fun preservesBooksContributedByAlternateSources() {
        val existing = listOf(
            book("remove-primary", "https://primary.example/remove"),
            book("alternate-only", "https://alternate.example/book")
        )

        val keep = PrimarySourceBookRetentionPolicy.keepIds(
            existingBooks = existing,
            incomingIds = listOf("new-primary"),
            ownedByCurrentSource = { it.startsWith("https://primary.example/") }
        )

        assertEquals(listOf("new-primary", "alternate-only"), keep)
    }

    @Test
    fun doesNotDuplicateIncomingIdsAlreadyPreserved() {
        val existing = listOf(book("shared", "https://alternate.example/shared"))

        val keep = PrimarySourceBookRetentionPolicy.keepIds(
            existingBooks = existing,
            incomingIds = listOf("shared"),
            ownedByCurrentSource = { false }
        )

        assertEquals(listOf("shared"), keep)
    }
}
