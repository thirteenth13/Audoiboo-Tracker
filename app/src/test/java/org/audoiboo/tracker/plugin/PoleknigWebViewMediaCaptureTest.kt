package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoleknigWebViewMediaCaptureTest {
    @Test fun acceptsBookPage() {
        assertTrue(PoleknigWebViewMediaCapture.isAllowedPage("https://poleknig.com/books/212841"))
    }

    @Test fun acceptsFilesResolver() {
        assertTrue(PoleknigWebViewMediaCapture.isResolverOrAudio("https://poleknig.com/files/123456"))
    }

    @Test fun acceptsExternalAudioAfterResolver() {
        assertTrue(PoleknigWebViewMediaCapture.isResolverOrAudio("https://cdn.example.net/audio/track-01.mp3"))
    }

    @Test fun rejectsUnrelatedPoleknigPage() {
        assertFalse(PoleknigWebViewMediaCapture.isAllowedPage("https://poleknig.com/"))
    }
}
