package org.audoiboo.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernSettingsDefaultsTest {
    @Test fun specializedSettingsHaveSafeDefaults() {
        val settings = ModernSettings()
        assertFalse(settings.voiceBoost)
        assertEquals(600, settings.gainMb)
        assertFalse(settings.seriesAutomationEnabled)
        assertFalse(settings.seriesAutoDownload)
        assertTrue(settings.seriesWifiOnly)
        assertEquals(3, settings.schemaVersion)
    }

    @Test fun specializedSettingsSurviveCopyWithoutAffectingCoreOptions() {
        val settings = ModernSettings(baseFolder = "Books", wifiOnly = true)
            .copy(voiceBoost = true, gainMb = 900, seriesAutomationEnabled = true, seriesAutoDownload = true, seriesWifiOnly = false)
        assertEquals("Books", settings.baseFolder)
        assertTrue(settings.wifiOnly)
        assertTrue(settings.voiceBoost)
        assertEquals(900, settings.gainMb)
        assertTrue(settings.seriesAutomationEnabled)
        assertTrue(settings.seriesAutoDownload)
        assertFalse(settings.seriesWifiOnly)
    }
}
