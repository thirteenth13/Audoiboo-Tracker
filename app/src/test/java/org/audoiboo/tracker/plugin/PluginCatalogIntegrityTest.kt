package org.audoiboo.tracker.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class PluginCatalogIntegrityTest {
    @Test
    fun catalogPackagesMatchChecksumsAndAreReadableArchives() {
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

            ZipFile(packageFile).use { zip ->
                val manifestEntry = zip.getEntry("plugin.json")
                assertNotNull("plugin.json missing from $id v$version", manifestEntry)
                val manifest = JSONObject(zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() })
                assertEquals("Manifest id mismatch", id, manifest.getString("id"))
                assertEquals("Manifest version mismatch", version, manifest.getInt("version"))

                val entriesInZip = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
                assertTrue("Empty plugin archive for $id v$version", entriesInZip.isNotEmpty())
                entriesInZip.forEach { zipEntry ->
                    val bytes = zip.getInputStream(zipEntry).use { it.readBytes() }
                    if (zipEntry.name.endsWith(".json")) {
                        val json = JSONObject(bytes.toString(Charsets.UTF_8))
                        assertNoAttributeBeforeCssComma(id, zipEntry.name, json)
                    }
                }
            }
        }
    }

    private fun assertNoAttributeBeforeCssComma(pluginId: String, path: String, value: Any?) {
        when (value) {
            is JSONObject -> value.keys().forEach { key -> assertNoAttributeBeforeCssComma(pluginId, "$path.$key", value.get(key)) }
            is JSONArray -> for (i in 0 until value.length()) assertNoAttributeBeforeCssComma(pluginId, "$path[$i]", value.get(i))
            is String -> assertTrue(
                "Invalid selector expression in $pluginId at $path: attribute extraction must be after a complete CSS selector or separated with ||",
                !Regex("@[A-Za-z][A-Za-z0-9_-]*\\s*,").containsMatchIn(value)
            )
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
