package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySourceMappingPolicyTest {
    private val existing = setOf("book-1", "book-2")

    @Test
    fun existingCanonicalBookMappingIsUsable() {
        assertTrue(LegacySourceMappingPolicy.isUsableCanonicalBookId("book-1", existing))
        assertEquals("book-1", LegacySourceMappingPolicy.canonicalBookIdForRead("book-1", existing))
    }

    @Test
    fun nullAndBlankMappingsAreNotUsable() {
        assertFalse(LegacySourceMappingPolicy.isUsableCanonicalBookId(null, existing))
        assertFalse(LegacySourceMappingPolicy.isUsableCanonicalBookId("", existing))
        assertNull(LegacySourceMappingPolicy.canonicalBookIdForRead(null, existing))
    }

    @Test
    fun staleCanonicalBookMappingIsNotUsable() {
        assertFalse(LegacySourceMappingPolicy.isUsableCanonicalBookId("deleted-book", existing))
        assertNull(LegacySourceMappingPolicy.canonicalBookIdForRead("deleted-book", existing))
    }
}
