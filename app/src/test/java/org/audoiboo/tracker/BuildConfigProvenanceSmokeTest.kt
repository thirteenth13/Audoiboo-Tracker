package org.audoiboo.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildConfigProvenanceSmokeTest {
    @Test
    fun buildConfigProvidesProvenanceFields() {
        assertTrue(BuildConfig.BUILD_COMMIT.isNotBlank())
        assertTrue(BuildConfig.BUILD_RUN.isNotBlank())
    }
}
