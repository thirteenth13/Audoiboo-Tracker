package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginBookUrlPolicyTest {
    @Test fun bazaRejectsAuthorPages() {
        assertNull(canonicalPluginBookUrl("baza-knig", "https://baza-knig.info/avtor-14224-roman-prokofev"))
        assertEquals(
            "https://baza-knig.info/audio-115251-zvezdnaja-krov-10-prozrachnye-dorogi-roman-prokofev",
            canonicalPluginBookUrl("baza-knig", "https://baza-knig.info/audio-115251-zvezdnaja-krov-10-prozrachnye-dorogi-roman-prokofev")
        )
    }

    @Test fun knigavuheDropsCommentFragments() {
        assertEquals(
            "https://m.knigavuhe.org/book/igra-kota/",
            canonicalPluginBookUrl("knigavuhe", "https://m.knigavuhe.org/book/igra-kota/#comments_block")
        )
    }

    @Test fun poleknigRejectsCatalogNavigation() {
        assertNull(canonicalPluginBookUrl("poleknig", "https://poleknig.com/books/novelties"))
        assertNull(canonicalPluginBookUrl("poleknig", "https://poleknig.com/books/popular"))
        assertEquals(
            "https://poleknig.com/books/212841",
            canonicalPluginBookUrl("poleknig", "https://poleknig.com/books/212841")
        )
    }

    @Test fun seriesPagesAreNotBookLookups() {
        assertNull(canonicalPluginBookUrl("izib", "https://izib.uk/serie8524?keepversion=1"))
        assertNull(canonicalPluginBookUrl("lis10book", "https://lis10book.com/serie/dlan-sistemy/"))
    }
}
