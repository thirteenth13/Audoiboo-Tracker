package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IzibWebViewMediaCaptureTest {
    @Test fun acceptsPdaArticlePage() {
        assertTrue(IzibWebViewMediaCapture.isAllowedPage("https://pda.izib.uk/art141591"))
    }

    @Test fun acceptsDesktopArticlePage() {
        assertTrue(IzibWebViewMediaCapture.isAllowedPage("https://izib.uk/art140801"))
    }

    @Test fun acceptsAudioMedia() {
        assertTrue(IzibWebViewMediaCapture.isAudioUrl("https://cdn.example.net/book/01.mp3"))
    }

    @Test fun rejectsReaderPageAsBook() {
        assertFalse(IzibWebViewMediaCapture.isAllowedPage("https://pda.izib.uk/reader4781"))
    }
}
