package org.audoiboo.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildArtifactNamePolicyTest {
    @Test
    fun shortCommitIsSuitableForArtifactLabel() {
        assertTrue(BuildProvenance.shortCommit.length in 1..12)
    }
}
