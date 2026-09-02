package org.audoiboo.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildCommitLengthPolicyTest {
    @Test
    fun commitLabelIsBounded() {
        assertTrue(BuildProvenance.shortCommit.length <= 12)
    }
}
