package org.audoiboo.tracker.ebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextPipelineTest {
    @Test fun preservesRussianYo() {
        assertEquals("Ёжик ещё идёт.", TtsTextPipeline.normalize("Ёжик ещё идёт.", "ru"))
    }

    @Test fun preservesUkrainianLettersAndApostrophe() {
        assertEquals("Їжак п'є ґрунтову воду — це є факт.", TtsTextPipeline.normalize("Їжак п’є ґрунтову воду – це є факт.", "uk"))
    }

    @Test fun collapsesWhitespaceButKeepsParagraphs() {
        assertEquals("Перший рядок\n\nДругий рядок", TtsTextPipeline.normalize("  Перший   рядок\n\n\n Другий\tрядок  ", "uk"))
    }

    @Test fun chunksAreBoundedAndDoNotSplitWords() {
        val text = (1..80).joinToString(" ") { "слово$it" } + "."
        val chunks = TtsTextPipeline.chunks(text, "ru", targetChars = 120, maxChars = 180)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 180 })
        assertEquals(chunks.indices.toList(), chunks.map { it.index })
        assertEquals(TtsTextPipeline.normalize(text, "ru"), chunks.joinToString(" ") { it.text })
    }

    @Test fun chunkingIsDeterministic() {
        val text = "Перше речення. Друге речення! Третє речення? ".repeat(30)
        val first = TtsTextPipeline.chunks(text, "uk", 120, 200)
        val second = TtsTextPipeline.chunks(text, "uk", 120, 200)
        assertEquals(first, second)
    }

    @Test fun veryLongSentenceIsSafelySplit() {
        val text = (1..100).joinToString(", ") { "частина$it" }
        val chunks = TtsTextPipeline.chunks(text, "uk", 120, 200)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 200 })
        assertTrue(chunks.none { it.text.startsWith(",") })
    }
}
