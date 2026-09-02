package org.audoiboo.tracker

import org.junit.Assert.assertTrue
import org.junit.Test

class BuildLabelVersionPolicyTest {
    @Test
    fun labelStartsWithVersion() {
        assertTrue(BuildProvenance.label.startsWith(BuildConfig.VERSION_NAME))
    }
}
