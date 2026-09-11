package org.audoiboo.tracker.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class IzibXsPlayerPolicyTest {
    private val hosts = setOf("pda.izib.uk", "izib.uk", "abookfiles.online")

    @Test
    fun legacyArrayRowsUseIndexFourAndKeepOrder() {
        val cfg = JSONObject("""{
          "mp3_url_prefix":"abookfiles.online/audio/book",
          "sign":"?token=abc",
          "tracks":[
            [1,"one","x","y","01.mp3"],
            [2,"two","x","y","02.mp3"]
          ]
        }""")

        assertEquals(
            listOf(
                "https://abookfiles.online/audio/book/01.mp3?token=abc",
                "https://abookfiles.online/audio/book/02.mp3?token=abc"
            ),
            IzibXsPlayerPolicy.extract(cfg, "https://pda.izib.uk/art141591", hosts)
        )
    }

    @Test
    fun objectAndStringRowsAreSupportedAndDeduplicated() {
        val cfg = JSONObject("""{
          "mp3_url_prefix":"//abookfiles.online/audio/book",
          "sign":"&sig=xyz",
          "tracks":[
            {"src":"03.m4a"},
            {"file":"03.m4a"},
            "04.mp3"
          ]
        }""")

        assertEquals(
            listOf(
                "https://abookfiles.online/audio/book/03.m4a?sig=xyz",
                "https://abookfiles.online/audio/book/04.mp3?sig=xyz"
            ),
            IzibXsPlayerPolicy.extract(cfg, "https://pda.izib.uk/art141591", hosts)
        )
    }

    @Test
    fun absoluteTrackUrlAndExistingQueryMergeSignCorrectly() {
        val cfg = JSONObject("""{
          "mp3_url_prefix":"https://abookfiles.online/audio/book",
          "sign":"?sig=xyz",
          "tracks":[
            {"url":"https://abookfiles.online/files/05.mp3?quality=1"}
          ]
        }""")

        assertEquals(
            listOf("https://abookfiles.online/files/05.mp3?quality=1&sig=xyz"),
            IzibXsPlayerPolicy.extract(cfg, "https://pda.izib.uk/art141591", hosts)
        )
    }

    @Test
    fun foreignHostAndNonAudioEntriesAreRejected() {
        val cfg = JSONObject("""{
          "mp3_url_prefix":"https://abookfiles.online/audio/book",
          "tracks":[
            {"file":"cover.jpg"},
            {"file":"https://evil.example/06.mp3"},
            {"file":"07.flac"}
          ]
        }""")

        assertEquals(
            listOf("https://abookfiles.online/audio/book/07.flac"),
            IzibXsPlayerPolicy.extract(cfg, "https://pda.izib.uk/art141591", hosts)
        )
    }
}
