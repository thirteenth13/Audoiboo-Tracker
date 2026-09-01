package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStoreDestinationPolicyTest {
    @Test
    fun audioUsesAudiobooksPrimaryDirectory() {
        assertEquals("Audiobooks", MediaStoreDestinationPolicy.root(isAudio = true))
        assertEquals(
            "Audiobooks/Audioboo/Роман Прокофьев/Звездная Кровь/10",
            MediaStoreDestinationPolicy.relativePath("Audioboo/Роман Прокофьев/Звездная Кровь/10", isAudio = true)
        )
    }

    @Test
    fun nonAudioPayloadsStayInDownload() {
        assertEquals("Download", MediaStoreDestinationPolicy.root(isAudio = false))
        assertEquals(
            "Download/Audioboo/Роман Прокофьев/Звездная Кровь/10",
            MediaStoreDestinationPolicy.relativePath("/Audioboo/Роман Прокофьев/Звездная Кровь/10/", isAudio = false)
        )
    }
}
