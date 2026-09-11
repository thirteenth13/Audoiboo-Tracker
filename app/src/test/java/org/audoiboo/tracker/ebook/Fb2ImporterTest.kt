package org.audoiboo.tracker.ebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Fb2ImporterTest {
    @Test
    fun `imports raw fb2 metadata and chapters`() {
        val imported = Fb2Importer.import(sampleFb2().toByteArray())

        assertEquals(EbookPayloadKind.FB2_XML, imported.kind)
        assertEquals("Тестовая книга", imported.document.title)
        assertEquals(listOf("Иван Иванов"), imported.document.authors)
        assertEquals("ru", imported.document.language)
        assertEquals("Цикл", imported.document.series)
        assertEquals(2, imported.document.seriesNumber)
        assertEquals(2, imported.document.chapters.size)
        assertEquals("Глава 1", imported.document.chapters[0].title)
        assertTrue(imported.document.chapters[0].text.contains("Первый абзац"))
        assertEquals("Глава 2", imported.document.chapters[1].title)
    }

    @Test
    fun `imports fb2 from zip`() {
        val zip = zipOf("book.fb2" to sampleFb2().toByteArray())
        val imported = Fb2Importer.import(zip)

        assertEquals(EbookPayloadKind.FB2_ZIP, imported.kind)
        assertEquals("Тестовая книга", imported.document.title)
        assertEquals(2, imported.document.chapters.size)
    }

    @Test
    fun `rejects zip traversal`() {
        val zip = zipOf("../book.fb2" to sampleFb2().toByteArray())

        assertThrows(EbookImportException::class.java) {
            Fb2Importer.import(zip)
        }
    }

    @Test
    fun `rejects zip without fb2`() {
        val zip = zipOf("readme.txt" to "not a book".toByteArray())

        assertThrows(EbookImportException::class.java) {
            Fb2Importer.import(zip)
        }
    }

    @Test
    fun `nested sections are emitted without duplicating child text`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>Nested</book-title><lang>ru</lang></title-info></description>
              <body>
                <section>
                  <title><p>Part</p></title>
                  <p>Parent intro.</p>
                  <section><title><p>Child</p></title><p>Child body.</p></section>
                </section>
              </body>
            </FictionBook>
        """.trimIndent()

        val chapters = Fb2Importer.import(xml.toByteArray()).document.chapters
        assertEquals(2, chapters.size)
        assertTrue(chapters[0].text.contains("Parent intro."))
        assertFalse(chapters[0].text.contains("Child body."))
        assertTrue(chapters[1].text.contains("Child body."))
    }

    @Test
    fun `does not treat annotation as narration`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info><book-title>A</book-title><annotation><p>Do not read this.</p></annotation></title-info></description>
              <body><section><title><p>One</p></title><p>Read this.</p></section></body>
            </FictionBook>
        """.trimIndent()

        val text = Fb2Importer.import(xml.toByteArray()).document.chapters.single().text
        assertTrue(text.contains("Read this."))
        assertFalse(text.contains("Do not read this."))
    }

    private fun sampleFb2(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
          <description>
            <title-info>
              <genre>sf</genre>
              <author><first-name>Иван</first-name><last-name>Иванов</last-name></author>
              <book-title>Тестовая книга</book-title>
              <lang>ru</lang>
              <sequence name="Цикл" number="2" />
            </title-info>
          </description>
          <body>
            <section><title><p>Глава 1</p></title><p>Первый абзац.</p><p>Второй абзац.</p></section>
            <section><title><p>Глава 2</p></title><p>Продолжение.</p></section>
          </body>
        </FictionBook>
    """.trimIndent()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
