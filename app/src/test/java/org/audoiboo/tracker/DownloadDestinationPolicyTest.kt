package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDestinationPolicyTest {
    private fun record(fileName: String) = ManagedDownloadRecord(
        id = "download-1",
        title = "Книга 3",
        series = "Серія",
        author = "Автор",
        bookUrl = "https://example.org/book",
        archiveUrl = "https://cdn.example.org/$fileName",
        relativeDir = "Audoiboo/Автор/Серія",
        bookDir = "Книга 3",
        fileName = fileName,
        state = ManagedDownloadState.QUEUED
    )

    @Test
    fun directAudioLivesInsideBookFolder() {
        val mp3 = record("Книга 3.mp3")
        val m4b = record("Книга 3.m4b")

        assertEquals("Audoiboo/Автор/Серія/Книга 3", DownloadDestinationPolicy.bookRelativeDir(mp3))
        assertEquals("Audoiboo/Автор/Серія/Книга 3", DownloadDestinationPolicy.bookRelativeDir(m4b))
        assertFalse(DownloadDestinationPolicy.shouldExtract(mp3.fileName, unpackEnabled = true))
        assertFalse(DownloadDestinationPolicy.shouldExtract(m4b.fileName, unpackEnabled = true))
    }

    @Test
    fun zipExtractsIntoSameBookFolderWhenEnabled() {
        val zip = record("Книга 3.zip")

        assertEquals("Audoiboo/Автор/Серія/Книга 3", DownloadDestinationPolicy.bookRelativeDir(zip))
        assertTrue(DownloadDestinationPolicy.shouldExtract(zip.fileName, unpackEnabled = true))
    }

    @Test
    fun zipKeptAsFileStillLivesInsideBookFolder() {
        val zip = record("Книга 3.zip")

        assertFalse(DownloadDestinationPolicy.shouldExtract(zip.fileName, unpackEnabled = false))
        assertEquals("Audoiboo/Автор/Серія/Книга 3", DownloadDestinationPolicy.bookRelativeDir(zip))
    }

    @Test
    fun rarAnd7zAreKeptInsideBookFolder() {
        assertFalse(DownloadDestinationPolicy.shouldExtract("Книга.rar", unpackEnabled = true))
        assertFalse(DownloadDestinationPolicy.shouldExtract("Книга.7z", unpackEnabled = true))
    }
}
