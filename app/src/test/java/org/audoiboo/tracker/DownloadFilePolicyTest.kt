package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFilePolicyTest {
    @Test
    fun keepsArchiveExtensions() {
        assertEquals(".zip", DownloadFilePolicy.extension("https://example.org/book.zip?token=1"))
        assertEquals(".rar", DownloadFilePolicy.extension("https://example.org/files/BOOK.RAR"))
        assertEquals(".7z", DownloadFilePolicy.extension("https://example.org/a.7z#download"))
    }

    @Test
    fun recognizesDirectAudioExtensions() {
        assertEquals(".mp3", DownloadFilePolicy.extension("https://cdn.example.org/audio/chapter.MP3?x=1"))
        assertEquals(".m4b", DownloadFilePolicy.extension("https://cdn.example.org/book.m4b"))
        assertEquals(".flac", DownloadFilePolicy.extension("https://cdn.example.org/book.flac"))
        assertTrue(DownloadFilePolicy.isDirectAudio("https://cdn.example.org/book.mp3"))
        assertFalse(DownloadFilePolicy.isDirectAudio("https://cdn.example.org/book.zip"))
    }

    @Test
    fun unknownLegacyUrlStillFallsBackToZip() {
        assertEquals(".zip", DownloadFilePolicy.extension("https://example.org/engine/go.php?id=42"))
        assertEquals(".zip", DownloadFilePolicy.extension("not a url"))
    }
}
