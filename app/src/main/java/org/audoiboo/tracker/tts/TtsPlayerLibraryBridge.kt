package org.audoiboo.tracker.tts

import android.content.Context
import java.io.File
import org.audoiboo.tracker.PlayerLibrary
import org.audoiboo.tracker.PlayerLibraryItem
import org.audoiboo.tracker.ebook.BookDocument

/** Makes committed TTS chapter files visible to the existing audiobook player/library. */
internal object TtsPlayerLibraryBridge {
    fun items(
        document: BookDocument,
        result: TtsBookGenerationResult,
        relativeRoot: String = "Audoiboo/TTS",
    ): List<PlayerLibraryItem> {
        val bookTitle = document.title?.trim().takeUnless { it.isNullOrBlank() } ?: "TTS Book"
        val author = document.authors.map(String::trim).filter(String::isNotBlank).joinToString(", ").ifBlank { null }
        val series = document.series?.trim().takeUnless { it.isNullOrBlank() }
        val relativePath = listOf(relativeRoot.trim().trimEnd('/'), safeSegment(bookTitle))
            .filter(String::isNotBlank)
            .joinToString("/")

        return result.chapters
            .filter { it.audioFile.isFile && it.audioFile.length() > 44L }
            .sortedBy { it.chapterIndex }
            .map { chapter ->
                PlayerLibraryItem(
                    uri = chapter.audioFile.toURI().toString(),
                    name = chapterDisplayName(chapter.chapterIndex, chapter.title, chapter.audioFile),
                    relativePath = relativePath,
                    bookTitle = bookTitle,
                    series = series,
                    author = author,
                )
            }
    }

    fun register(
        context: Context,
        document: BookDocument,
        result: TtsBookGenerationResult,
        relativeRoot: String = "Audoiboo/TTS",
    ): List<PlayerLibraryItem> {
        val items = items(document, result, relativeRoot)
        items.forEach { item ->
            PlayerLibrary.register(
                context = context,
                uri = android.net.Uri.parse(item.uri),
                name = item.name,
                relativePath = item.relativePath,
                bookTitle = item.bookTitle,
                series = item.series,
                author = item.author,
            )
        }
        return items
    }

    private fun chapterDisplayName(index: Int, title: String, file: File): String {
        val cleanTitle = title.trim().takeIf(String::isNotBlank)
        return if (cleanTitle != null) "%04d - %s.wav".format(index + 1, cleanTitle)
        else file.name
    }

    private fun safeSegment(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .trim('.')
        .ifBlank { "TTS Book" }
}
