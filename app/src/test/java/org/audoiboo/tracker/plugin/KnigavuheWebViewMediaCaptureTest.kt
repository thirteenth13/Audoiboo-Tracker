package org.audoiboo.tracker.plugin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnigavuheWebViewMediaCaptureTest {
    @Test fun acceptsDesktopBookAudio() {
        assertTrue(KnigavuheWebViewMediaCapture.isBookAudio(
            "https://u11.knigavuhe.org/1/audio/18350/hash/00.mp3"
        ))
    }

    @Test fun acceptsMobileLargeSegment() {
        assertTrue(KnigavuheWebViewMediaCapture.isBookAudio(
            "https://u11.knigavuhe.org/1/audio/18350/mobile3/hash/igra-kota-kniga-vtoraja-4.mp3"
        ))
    }

    @Test fun acceptsNumberedTrackWithTitle() {
        assertTrue(KnigavuheWebViewMediaCapture.isLikelyTrackLabel(
            "01 Закрытый мир Коты Хираги (I) 01 09:41"
        ))
    }

    @Test fun acceptsLargeSegmentLabel() {
        assertTrue(KnigavuheWebViewMediaCapture.isLikelyTrackLabel(
            "Игра престолов_4 1:59:31"
        ))
    }

    @Test fun rejectsOrdinaryPageTextAsTrack() {
        assertFalse(KnigavuheWebViewMediaCapture.isLikelyTrackLabel(
            "Автор: Джордж Мартин"
        ))
    }

    @Test fun rejectsAdvertisingMedia() {
        assertFalse(KnigavuheWebViewMediaCapture.isBookAudio(
            "https://rutube.ru/video/ad-player.webm"
        ))
    }

    @Test fun rejectsThirdPartyMp3() {
        assertFalse(KnigavuheWebViewMediaCapture.isBookAudio(
            "https://example.org/audio/18350/00.mp3"
        ))
    }

    @Test fun rejectsNonAudioKnigavuheUrl() {
        assertFalse(KnigavuheWebViewMediaCapture.isBookAudio(
            "https://knigavuhe.org/book/igra-kota-kniga-vtoraja/"
        ))
    }

    @Test fun acceptsMobileBookPage() {
        assertTrue(KnigavuheWebViewMediaCapture.isAllowedPage(
            "https://m.knigavuhe.org/book/igra-kota-kniga-vtoraja/"
        ))
    }
}
