package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageTreePolicyTest {
    @Test fun restoredTreeWinsWhenPermissionStillExists() {
        assertEquals("content://restored", StorageTreePolicy.select("content://restored", true, "content://fallback", true))
    }

    @Test fun staleRestoredTreeFallsBackToCurrentAccessibleTree() {
        assertEquals("content://fallback", StorageTreePolicy.select("content://restored", false, "content://fallback", true))
    }

    @Test fun inaccessibleTreesFallBackToSystemDownloads() {
        assertNull(StorageTreePolicy.select("content://restored", false, "content://fallback", false))
        assertNull(StorageTreePolicy.select(null, false, null, false))
    }
}
