package org.audoiboo.tracker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlibustaTtsUiPolicyTest {
    @Test
    fun acceptsBookPagesFromSupportedFlibustaHosts() {
        assertTrue(isFlibustaBookUrl("https://flibusta.site/b/123"))
        assertTrue(isFlibustaBookUrl("https://flibusta.one/b/abc-123/"))
        assertTrue(isFlibustaBookUrl("https://flibusta.name/b/42"))
    }

    @Test
    fun rejectsNonBookPagesAndLookalikeHosts() {
        assertFalse(isFlibustaBookUrl("https://flibusta.site/a/123"))
        assertFalse(isFlibustaBookUrl("https://flibusta.site/b/123/fb2"))
        assertFalse(isFlibustaBookUrl("https://evilflibusta.site/b/123"))
        assertFalse(isFlibustaBookUrl("https://example.org/b/123"))
        assertFalse(isFlibustaBookUrl("not a url"))
    }
}
