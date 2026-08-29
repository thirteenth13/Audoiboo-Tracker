package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveEntryPolicyTest {
    @Test fun acceptsNormalNestedPaths() {
        assertEquals("disc1/01.mp3", ArchiveEntryPolicy.safeRelativePath("disc1/01.mp3"))
        assertEquals("disc1/01.mp3", ArchiveEntryPolicy.safeRelativePath("disc1\\01.mp3"))
    }

    @Test fun rejectsTraversal() {
        assertNull(ArchiveEntryPolicy.safeRelativePath("../escape.mp3"))
        assertNull(ArchiveEntryPolicy.safeRelativePath("disc/../../escape.mp3"))
        assertNull(ArchiveEntryPolicy.safeRelativePath("disc/./01.mp3"))
    }

    @Test fun rejectsAbsoluteAndDrivePaths() {
        assertNull(ArchiveEntryPolicy.safeRelativePath("/etc/passwd"))
        assertNull(ArchiveEntryPolicy.safeRelativePath("C:/temp/01.mp3"))
        assertNull(ArchiveEntryPolicy.safeRelativePath("D:\\temp\\01.mp3"))
    }

    @Test fun rejectsEmptyAndNulNames() {
        assertNull(ArchiveEntryPolicy.safeRelativePath(""))
        assertNull(ArchiveEntryPolicy.safeRelativePath("folder/\u0000bad.mp3"))
    }
}
