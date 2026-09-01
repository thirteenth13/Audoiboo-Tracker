package org.audoiboo.tracker.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PluginCatalogIntegrityTest {
    @Test
    fun catalogChecksumsMatchCommittedPackages() {
        val pluginsDir = sequenceOf(File("plugins"), File("../plugins"))
            .firstOrNull { File(it, "catalog.json").isFile }
            ?: error("plugins/catalog.json not found from ${File(".").absolutePath}")
        val catalog = JSONObject(File(pluginsDir, "catalog.json").readText())
        val entries = catalog.getJSONArray("plugins")
        assertTrue(entries.length() > 0)

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val id = entry.getString("id")
            val version = entry.getInt("version")
            val expected = entry.getString("sha256").lowercase()
            val packageFile = File(pluginsDir, "packages/$id-$version.abplugin")
            assertTrue("Missing package for $id v$version", packageFile.isFile)
            assertEquals("Checksum mismatch for $id v$version", expected, sha256(packageFile))
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
