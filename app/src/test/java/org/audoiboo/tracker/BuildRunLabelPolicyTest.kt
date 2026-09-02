package org.audoiboo.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildRunLabelPolicyTest {
    @Test
    fun labelIncludesCiMarker() {
        assertTrue(BuildProvenance.label.contains("CI "))
    }
}
