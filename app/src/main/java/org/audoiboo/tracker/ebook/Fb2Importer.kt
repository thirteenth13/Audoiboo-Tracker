package org.audoiboo.tracker.ebook

import org.audoiboo.tracker.ArchiveEntryPolicy
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object Fb2Importer {
    private const val MAX_ZIP_ENTRIES = 128
    private const val MAX_FB2_BYTES = 48L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 64L * 1024 * 1024
    private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun import(payload: ByteArray): ImportedEbook {
        if (payload.size < 4) throw EbookImportException("FB2 payload is empty or too small")
        return if (looksLikeZip(payload)) {
            ImportedEbook(EbookPayloadKind.FB2_ZIP, parseXml(extractFb2FromZip(payload)))
        } else {
            ImportedEbook(EbookPayloadKind.FB2_XML, parseXml(payload))
        }
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    private fun extractFb2FromZip(zipBytes: ByteArray): ByteArray {
        var entries = 0
        var totalBytes = 0L
        var fb2: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ZIP_ENTRIES) throw EbookImportException("FB2 archive has too many entries")

                val safeName = ArchiveEntryPolicy.safeRelativePath(entry.name)
                    ?: throw EbookImportException("Unsafe ZIP entry path: ${entry.name}")
                if (entry.isDirectory) continue

                val lower = safeName.lowercase()
                val isFb2 = lower.endsWith(".fb2")
                var entryBytes = 0L
                val output = if (isFb2 && fb2 == null) ByteArrayOutputStream() else null
                val buffer = ByteArray(16 * 1024)

                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    entryBytes += read
                    totalBytes += read
                    if (entryBytes > MAX_FB2_BYTES) throw EbookImportException("FB2 archive entry is too large")
                    if (totalBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) throw EbookImportException("FB2 archive expands beyond safety limit")
                    output?.write(buffer, 0, read)
                }

                if (output != null) fb2 = output.toByteArray()
                zip.closeEntry()
            }
        }

        return fb2 ?: throw EbookImportException("ZIP does not contain an FB2 file")
    }

    private fun parseXml(bytes: ByteArray): BookDocument {
        if (bytes.size > MAX_FB2_BYTES) throw EbookImportException("FB2 XML is too large")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setAttribute(ACCESS_EXTERNAL_DTD, "") }
            runCatching { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") }
        }

        val dom = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw EbookImportException("Invalid FB2 XML", e)
        }

        val root = dom.documentElement ?: throw EbookImportException("FB2 XML has no root element")
        if (localName(root) != "FictionBook") throw EbookImportException("XML is not FictionBook")

        val titleInfo = descendants(root, "title-info").firstOrNull()
        val title = titleInfo?.let { firstDescendantText(it, "book-title") }
        val language = titleInfo?.let { firstDescendantText(it, "lang") }
        val authors = titleInfo?.let(::parseAuthors).orEmpty()
        val sequence = titleInfo?.let { descendants(it, "sequence").firstOrNull() }
        val series = sequence?.getAttribute("name")?.trim()?.takeIf { it.isNotEmpty() }
        val seriesNumber = sequence?.getAttribute("number")?.trim()?.toIntOrNull()

        val chapters = mutableListOf<BookChapter>()
        childElements(root).filter { localName(it) == "body" }.forEach { body ->
            childElements(body).filter { localName(it) == "section" }.forEach { section ->
                collectSections(section, chapters)
            }
        }

        if (chapters.isEmpty()) {
            val bodyBlocks = childElements(root)
                .filter { localName(it) == "body" }
                .flatMap(::textBlocksExcludingNestedSections)
                .map(::cleanText)
                .filter(String::isNotBlank)
            if (bodyBlocks.isNotEmpty()) chapters += BookChapter(0, title ?: "Book", bodyBlocks)
        }

        if (chapters.isEmpty()) throw EbookImportException("FB2 contains no readable chapters")

        return BookDocument(
            title = title,
            authors = authors,
            language = language,
            series = series,
            seriesNumber = seriesNumber,
            chapters = chapters.mapIndexed { index, chapter -> chapter.copy(index = index) }
        )
    }

    private fun collectSections(section: Element, output: MutableList<BookChapter>) {
        val title = sectionTitle(section)
        val blocks = textBlocksExcludingNestedSections(section)
            .map(::cleanText)
            .filter(String::isNotBlank)

        val nested = childElements(section).filter { localName(it) == "section" }
        if (blocks.isNotEmpty()) {
            output += BookChapter(output.size, title ?: "Chapter ${output.size + 1}", blocks)
        }
        nested.forEach { collectSections(it, output) }
    }

    private fun sectionTitle(section: Element): String? {
        val title = childElements(section).firstOrNull { localName(it) == "title" } ?: return null
        return cleanText(title.textContent).takeIf(String::isNotBlank)
    }

    private fun textBlocksExcludingNestedSections(element: Element): List<String> {
        val out = mutableListOf<String>()
        fun visit(node: Node, insideNestedSection: Boolean) {
            if (node is Element) {
                val name = localName(node)
                if (node !== element && name == "section") return
                if (name in setOf("p", "subtitle", "text-author", "v")) {
                    val text = cleanText(node.textContent)
                    if (text.isNotBlank()) out += text
                    return
                }
                if (name in setOf("annotation", "binary")) return
            }
            val children = node.childNodes
            for (i in 0 until children.length) visit(children.item(i), insideNestedSection)
        }
        visit(element, false)
        return out
    }

    private fun parseAuthors(titleInfo: Element): List<String> = descendants(titleInfo, "author")
        .mapNotNull { author ->
            val first = firstDescendantText(author, "first-name")
            val middle = firstDescendantText(author, "middle-name")
            val last = firstDescendantText(author, "last-name")
            val nick = firstDescendantText(author, "nickname")
            listOfNotNull(first, middle, last).joinToString(" ").trim().takeIf { it.isNotEmpty() } ?: nick
        }
        .distinct()

    private fun firstDescendantText(root: Element, name: String): String? = descendants(root, name)
        .firstOrNull()?.textContent?.let(::cleanText)?.takeIf(String::isNotBlank)

    private fun descendants(root: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        fun walk(node: Node) {
            val children = node.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                if (child is Element) {
                    if (localName(child) == name) out += child
                    walk(child)
                }
            }
        }
        walk(root)
        return out
    }

    private fun childElements(node: Node): List<Element> {
        val out = mutableListOf<Element>()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element) out += child
        }
        return out
    }

    private fun localName(element: Element): String = element.localName ?: element.tagName.substringAfter(':')

    private fun cleanText(value: String): String = value
        .replace('\u00a0', ' ')
        .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
        .replace(Regex(" *\\n+ *"), "\n")
        .trim()
}
