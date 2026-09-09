package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Test

class BazaCoverRecoveryTest {
    @Test
    fun derivesCoverBesidePlaylistFromBazaSlug() {
        val html = """
            <script>
              player = { file: "https://redirectto.cc/s01/123157/123157.pl.txt" };
            </script>
        """.trimIndent()

        assertEquals(
            "https://redirectto.cc/s01/123157/hozjain-stuzni-tom-3-maksim-petrov.jpg",
            BazaCoverRecovery.candidateFrom(
                "https://baza-knig.info/audio-115251-hozjain-stuzni-tom-3-maksim-petrov",
                html
            )
        )
    }

    @Test
    fun prefersExplicitRedirecttoCoverFromHtml() {
        val html = """
            <img src="https://redirectto.cc/s01/123157/custom-cover.jpg">
            <script>player={file:"https://redirectto.cc/s01/123157/123157.pl.txt"}</script>
        """.trimIndent()

        assertEquals(
            "https://redirectto.cc/s01/123157/custom-cover.jpg",
            BazaCoverRecovery.candidateFrom(
                "https://baza-knig.info/audio-115251-other-slug",
                html
            )
        )
    }
}
