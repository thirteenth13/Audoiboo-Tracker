package org.audoiboo.tracker

import org.audoiboo.tracker.plugin.alternateIzibUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRepairPolicyTest {
    @Test
    fun audiobooGoPhpIsTreatedAsArchiveRedirect() {
        assertTrue(
            ManagedDownloads.isAudiobooArchiveRedirect(
                "https://audioboo.org/litrpg/118927-korablev-rodion-drugaja-storona-26-lichnyj-vrag.html",
                "https://audioboo.org/go.php?id=118927"
            )
        )
        assertFalse(
            ManagedDownloads.isAudiobooArchiveRedirect(
                "https://example.org/book/1",
                "https://example.org/go.php?id=1"
            )
        )
    }

    @Test
    fun izibPdaUrlHasDesktopMirrorFallback() {
        assertEquals(
            "https://izib.uk/serie8524?keepversion=1",
            alternateIzibUrl("https://pda.izib.uk/serie8524")
        )
        assertEquals(
            "https://pda.izib.uk/art141591",
            alternateIzibUrl("https://izib.uk/art141591?keepversion=1")
        )
    }
}
