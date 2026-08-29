package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedDownloadPersistenceTest {
    @Test
    fun entityRoundTripPreservesDownloadState() {
        val record = ManagedDownloadRecord(
            id = "id-1",
            title = "Book",
            series = "Series",
            author = "Author",
            bookUrl = "https://example/book",
            archiveUrl = "https://example/archive.zip",
            relativeDir = "Audoiboo/Author/Series",
            bookDir = "Book",
            fileName = "Book.zip",
            state = ManagedDownloadState.PAUSED,
            downloaded = 1234L,
            total = 5678L,
            error = "network",
            createdAt = 42L
        )

        assertEquals(record, record.toEntity().toRecord())
    }

    @Test
    fun unknownStoredStateFailsSafe() {
        val entity = ManagedDownloadEntity(
            id = "id-2",
            title = "Book",
            series = "Series",
            author = null,
            bookUrl = "book",
            archiveUrl = "archive",
            relativeDir = "dir",
            bookDir = "book",
            fileName = "book.zip",
            state = "UNKNOWN_FUTURE_STATE",
            downloaded = 0L,
            total = -1L,
            error = null,
            createdAt = 1L
        )

        val restored = entity.toRecord()
        assertEquals(ManagedDownloadState.FAILED, restored.state)
        assertNull(restored.author)
        assertNull(restored.error)
    }
}
