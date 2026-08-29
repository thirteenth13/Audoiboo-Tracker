package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StoragePathPolicyTest {
    @Test fun normalizesSafeRelativeDirectories() {
        assertEquals("Audoiboo/Series/Book", StoragePathPolicy.normalizeRelativeDir("Audoiboo\\Series//Book/"))
        assertEquals("", StoragePathPolicy.normalizeRelativeDir("///"))
    }

    @Test fun rejectsTraversalAndUnsafeNames() {
        assertNull(StoragePathPolicy.normalizeRelativeDir("Audoiboo/../Other"))
        assertNull(StoragePathPolicy.normalizeRelativeDir("./Book"))
        assertTrue(StoragePathPolicy.validFileName("01.mp3"))
        assertFalse(StoragePathPolicy.validFileName("../01.mp3"))
        assertFalse(StoragePathPolicy.validFileName("dir/01.mp3"))
        assertFalse(StoragePathPolicy.validFileName(""))
    }
}
