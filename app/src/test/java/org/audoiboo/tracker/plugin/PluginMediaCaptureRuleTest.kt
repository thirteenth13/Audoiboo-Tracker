package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginMediaCaptureRuleTest {
    @Test fun loadsDataOnlyCaptureRule() {
        val file = File.createTempFile("capture", ".json")
        try {
            file.writeText("""{"media":{"pagePathRegex":"^/audio-","mediaExtensions":["mp3"],"mediaHosts":["redirectto.cc"],"activateIntervalMs":450,"downloadType":"DIRECT_FILE"}}""")
            val rule = PluginMediaCaptureRule.load(file)
            assertEquals(450L, rule.activateIntervalMs)
            assertEquals(DownloadType.DIRECT_FILE, rule.downloadType)
            assertTrue("mp3" in rule.mediaExtensions)
        } finally { file.delete() }
    }

    @Test fun enforcesManifestPageAndDownloadHosts() {
        val manifest = PluginPackageManifest(
            id="baza-knig", name="Baza", version=9, apiVersion=1,
            hosts=setOf("baza-knig.info"), capabilities=setOf(SourceCapability.DOWNLOAD_RESOLUTION),
            permissions=PluginPermissions(networkHosts=setOf("baza-knig.info"), downloadHosts=setOf("baza-knig.info", "redirectto.cc"))
        )
        val rule = PluginMediaCaptureRule.load(File.createTempFile("capture", ".json").apply {
            writeText("""{"media":{"pagePathRegex":"^/audio-","mediaExtensions":["mp3"],"mediaHosts":["redirectto.cc"]}}""")
            deleteOnExit()
        })
        assertTrue(PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, "https://baza-knig.info/audio-1-book"))
        assertFalse(PluginWebViewMediaCaptureRuntime.isAllowedPage(manifest, rule, "https://example.com/audio-1-book"))
        assertTrue(PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, "https://x.redirectto.cc/a/02.mp3"))
        assertFalse(PluginWebViewMediaCaptureRuntime.isMedia(manifest, rule, "https://evil.example/a/02.mp3"))
    }
}
