package org.audoiboo.tracker.ebook

/** Neutral representation shared by FB2 now and EPUB/TTS later. */
data class BookDocument(
    val title: String?,
    val authors: List<String>,
    val language: String?,
    val series: String?,
    val seriesNumber: Int?,
    val chapters: List<BookChapter>
)

data class BookChapter(
    val index: Int,
    val title: String,
    val blocks: List<String>
) {
    val text: String get() = blocks.joinToString("\n\n")
}

enum class EbookPayloadKind {
    FB2_XML,
    FB2_ZIP
}

data class ImportedEbook(
    val kind: EbookPayloadKind,
    val document: BookDocument
)

class EbookImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
