package org.audoiboo.tracker

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PluginManagementDiagnosticsUiTest {
    @Test
    fun pluginManagerKeepsDiagnosticsButtonAndBuildLabel() {
        val file = sequenceOf(
            File("src/main/java/org/audoiboo/tracker/PluginManagementActivity.kt"),
            File("app/src/main/java/org/audoiboo/tracker/PluginManagementActivity.kt")
        ).firstOrNull { it.isFile } ?: error("PluginManagementActivity.kt not found")
        val source = file.readText()
        assertTrue(source.contains("Text(\"Діагностика\")"))
        assertTrue(source.contains("BuildProvenance.label"))
    }
}
