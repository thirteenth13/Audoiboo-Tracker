package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
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
}
