package org.audoiboo.tracker.ebook

import java.text.Normalizer

data class TtsTextChunk(
    val index: Int,
    val text: String,
)

object TtsTextPipeline {
    const val DEFAULT_TARGET_CHARS = 900
    const val DEFAULT_MAX_CHARS = 1500

    fun normalize(text: String, language: String?): String {
        val lang = language.orEmpty().lowercase()
        var value = Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace('\u00a0', ' ')
            .replace('\u200b'.toString(), "")
            .replace('\u00ad'.toString(), "")
            .replace('“', '"').replace('”', '"').replace('„', '"')
            .replace('’', '\'').replace('`', '\'')
            .replace('–', '—')

        // Keep language-specific letters intact. In particular, never rewrite е/ё or Ukrainian і/ї/є/ґ.
        value = when {
            lang.startsWith("ru") -> normalizeRussian(value)
            lang.startsWith("uk") || lang.startsWith("ua") -> normalizeUkrainian(value)
            else -> value
        }

        return value
            .lines()
            .map { line -> line.replace(Regex("[\\t\\x0B\\f\\r ]+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun normalizeRussian(text: String): String = text
        .replace(Regex("(?i)\\bг\\.(?=\\s*\\d)"), "год ")
        .replace(Regex("(?i)\\bгг\\.(?=\\s*\\d)"), "годы ")

    private fun normalizeUkrainian(text: String): String = text
        .replace(Regex("(?i)\\bр\\.(?=\\s*\\d)"), "рік ")
        .replace(Regex("(?i)\\bрр\\.(?=\\s*\\d)"), "роки ")

    fun chunks(
        text: String,
        language: String?,
        targetChars: Int = DEFAULT_TARGET_CHARS,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): List<TtsTextChunk> {
        require(targetChars in 100..maxChars)
        require(maxChars >= 200)
        val normalized = normalize(text, language)
        if (normalized.isBlank()) return emptyList()

        val paragraphs = normalized.split(Regex("\\n{2,}"))
            .map(String::trim)
            .filter(String::isNotBlank)
        val pieces = paragraphs.flatMap { splitParagraph(it, maxChars) }
        val out = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                out += current.toString().trim()
                current.clear()
            }
        }

        for (piece in pieces) {
            if (piece.length > maxChars) {
                flush()
                out += hardSplit(piece, maxChars)
                continue
            }
            val separator = if (current.isEmpty()) "" else " "
            if (current.length + separator.length + piece.length > maxChars ||
                (current.length >= targetChars && endsNaturally(current.toString()))) {
                flush()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        flush()
        return out.filter(String::isNotBlank).mapIndexed { index, chunk -> TtsTextChunk(index, chunk) }
    }

    private fun splitParagraph(paragraph: String, maxChars: Int): List<String> {
        if (paragraph.length <= maxChars) return listOf(paragraph)
        val sentences = Regex("(?<=[.!?…])\\s+").split(paragraph)
            .map(String::trim).filter(String::isNotBlank)
        if (sentences.size <= 1) return hardSplit(paragraph, maxChars)
        return sentences.flatMap { if (it.length <= maxChars) listOf(it) else hardSplit(it, maxChars) }
    }

    private fun hardSplit(text: String, maxChars: Int): List<String> {
        val result = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > maxChars) {
            val window = rest.substring(0, maxChars + 1)
            val cut = listOf(window.lastIndexOf("; "), window.lastIndexOf(": "), window.lastIndexOf(", "), window.lastIndexOf(' '))
                .filter { it >= maxChars / 2 }
                .maxOrNull() ?: maxChars
            result += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) result += rest
        return result
    }

    private fun endsNaturally(text: String): Boolean = text.lastOrNull() in setOf('.', '!', '?', '…')
}
