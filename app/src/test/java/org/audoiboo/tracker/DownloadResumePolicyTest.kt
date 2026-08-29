package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadResumePolicyTest {
    @Test fun appendsOnlyWhenServerRangeStartsAtStagingLength() {
        assertTrue(DownloadResumePolicy.canAppend(100, 206, "bytes 100-199/200"))
        assertFalse(DownloadResumePolicy.canAppend(100, 206, "bytes 0-99/200"))
        assertFalse(DownloadResumePolicy.canAppend(100, 206, null))
        assertFalse(DownloadResumePolicy.canAppend(100, 200, "bytes 100-199/200"))
    }

    @Test fun rejectsMalformedOrImpossibleContentRanges() {
        assertEquals(null, DownloadResumePolicy.parseContentRange("bytes 20-10/100"))
        assertEquals(null, DownloadResumePolicy.parseContentRange("bytes 0-100/100"))
        assertEquals(null, DownloadResumePolicy.parseContentRange("items 0-10/100"))
    }

    @Test fun serverTotalWinsOverDerivedContentLength() {
        assertEquals(1000L, DownloadResumePolicy.expectedTotal(500, 100, "bytes 500-599/1000"))
        assertEquals(600L, DownloadResumePolicy.expectedTotal(500, 100, null))
        assertEquals(-1L, DownloadResumePolicy.expectedTotal(0, -1, null))
    }
}
