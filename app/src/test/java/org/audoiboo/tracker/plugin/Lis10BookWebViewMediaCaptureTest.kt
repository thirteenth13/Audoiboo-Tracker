package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Lis10BookWebViewMediaCaptureTest {
    @Test fun acceptsAudioBookPage() {
        assertTrue(Lis10BookWebViewMediaCapture.isAllowedPage("https://lis10book.com/audio/dlan-sistemy-kniga-3/"))
    }

    @Test fun acceptsCommonAudioFormats() {
        assertTrue(Lis10BookWebViewMediaCapture.isAudioUrl("https://cdn.example.net/books/track01.mp3"))
        assertTrue(Lis10BookWebViewMediaCapture.isAudioUrl("https://cdn.example.net/books/master.m3u8?token=x"))
    }

    @Test fun rejectsNonAudioResource() {
        assertFalse(Lis10BookWebViewMediaCapture.isAudioUrl("https://lis10book.com/assets/app.js"))
    }

    @Test fun resolvesRootRelativeAudioAgainstBookPage() {
        assertEquals(
            "https://lis10book.com/media/books/chapter-03.mp3?token=x",
            Lis10BookWebViewMediaCapture.normalizeMediaUrl(
                "https://lis10book.com/audio/dlan-sistemy-kniga-3/",
                "/media/books/chapter-03.mp3?token=x"
            )
        )
    }

    @Test fun resolvesPathRelativeAndProtocolRelativeAudio() {
        assertEquals(
            "https://lis10book.com/audio/media/chapter-04.m4a",
            Lis10BookWebViewMediaCapture.normalizeMediaUrl(
                "https://lis10book.com/audio/dlan-sistemy-kniga-3/",
                "../media/chapter-04.m4a"
            )
        )
        assertEquals(
            "https://cdn.example.net/books/chapter-05.mp3",
            Lis10BookWebViewMediaCapture.normalizeMediaUrl(
                "https://lis10book.com/audio/dlan-sistemy-kniga-3/",
                "//cdn.example.net/books/chapter-05.mp3"
            )
        )
    }

    @Test fun preservesAbsoluteCdnAudioAndRejectsNonAudioRelativeUrl() {
        assertEquals(
            "https://cdn.example.net/books/chapter-06.mp3?token=abc",
            Lis10BookWebViewMediaCapture.normalizeMediaUrl(
                "https://lis10book.com/audio/dlan-sistemy-kniga-3/",
                "https://cdn.example.net/books/chapter-06.mp3?token=abc"
            )
        )
        assertNull(
            Lis10BookWebViewMediaCapture.normalizeMediaUrl(
                "https://lis10book.com/audio/dlan-sistemy-kniga-3/",
                "/api/p/dlan-sistemy-kniga-3"
            )
        )
    }

    @Test fun mediaKeyIgnoresSignedQueryTokens() {
        assertEquals(
            Lis10BookWebViewMediaCapture.mediaKey("https://cdn.example.net/books/track01.mp3?token=one"),
            Lis10BookWebViewMediaCapture.mediaKey("https://cdn.example.net/books/track01.mp3?token=two")
        )
    }

    @Test fun extractsTrackNumberForStableOrdering() {
        assertEquals(12, Lis10BookWebViewMediaCapture.trackNumber("https://cdn.example.net/books/chapter-12.mp3"))
    }
}
