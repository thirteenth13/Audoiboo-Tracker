package org.audoiboo.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildProvenanceTest {
    @Test
    fun buildProvenanceLabelContainsVersionCommitAndRun() {
        val label = BuildProvenance.label
        assertTrue(label.contains(BuildConfig.VERSION_NAME))
        assertTrue(label.contains(BuildProvenance.shortCommit))
        assertTrue(label.contains("CI ${BuildConfig.BUILD_RUN}"))
    }
}
