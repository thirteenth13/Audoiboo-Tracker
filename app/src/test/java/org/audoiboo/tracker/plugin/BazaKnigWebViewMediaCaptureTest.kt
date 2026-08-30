package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BazaKnigWebViewMediaCaptureTest {
    @Test fun acceptsBazaBookPage() {
        assertTrue(BazaKnigWebViewMediaCapture.isAllowedPage("https://baza-knig.info/audio-115251-book"))
    }

    @Test fun acceptsRedirectToAudio() {
        assertTrue(BazaKnigWebViewMediaCapture.isBookAudio("https://j3wccg4mgjcw.redirectto.cc/s01/1/1/5/2/5/1/0.mp3"))
    }

    @Test fun rejectsThirdPartyMp3() {
        assertFalse(BazaKnigWebViewMediaCapture.isBookAudio("https://example.com/audio/0.mp3"))
    }

    @Test fun sortsNumericTracksNaturally() {
        assertEquals(10, BazaKnigWebViewMediaCapture.trackNumber("https://x.redirectto.cc/a/10.mp3"))
        assertEquals(2, BazaKnigWebViewMediaCapture.trackNumber("https://x.redirectto.cc/a/2.mp3"))
    }

    @Test fun acceptsNumberedTrackLabel() {
        assertTrue(BazaKnigWebViewMediaCapture.isLikelyTrackLabel("01. Глава первая"))
    }

    @Test fun acceptsNamedChapterLabel() {
        assertTrue(BazaKnigWebViewMediaCapture.isLikelyTrackLabel("Глава 12"))
    }

    @Test fun rejectsDescriptionTextAsTrack() {
        assertFalse(BazaKnigWebViewMediaCapture.isLikelyTrackLabel("Роман Прокофьев — Звездная кровь"))
    }
}
