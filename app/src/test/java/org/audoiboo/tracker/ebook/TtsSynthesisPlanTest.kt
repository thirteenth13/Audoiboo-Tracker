package org.audoiboo.tracker.ebook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSynthesisPlanTest {
    private fun sampleDocument() = BookDocument(
        title = "Книга",
        authors = listOf("Автор"),
        language = "uk",
        series = "Цикл",
        seriesNumber = 2,
        chapters = listOf(
            BookChapter(0, "Розділ 1", listOf("Перше речення. ".repeat(30))),
            BookChapter(1, "Розділ 2", listOf("Друге речення. ".repeat(25))),
        ),
    )

    @Test fun planPreservesChapterBoundariesAndGlobalOrder() {
        val plan = TtsSynthesisPlanner.build(sampleDocument(), targetChars = 120, maxChars = 180)
        assertEquals(2, plan.chapters.size)
        assertTrue(plan.chapters.all { it.chunks.isNotEmpty() })
        assertEquals(plan.chunks.indices.toList(), plan.chunks.map { it.globalIndex })
        assertEquals(0, plan.chapters[0].chunks.first().chapterIndex)
        assertEquals(1, plan.chapters[1].chunks.first().chapterIndex)
    }

    @Test fun checkpointKeysAreStableAndUnique() {
        val first = TtsSynthesisPlanner.build(sampleDocument(), 120, 180)
        val second = TtsSynthesisPlanner.build(sampleDocument(), 120, 180)
        assertEquals(first, second)
        assertEquals(first.chunkCount, first.chunks.map { it.checkpointKey }.toSet().size)
    }

    @Test fun fingerprintChangesWhenReadableTextChanges() {
        val original = sampleDocument()
        val modified = original.copy(
            chapters = original.chapters.mapIndexed { index, chapter ->
                if (index == 0) chapter.copy(blocks = listOf("Інший текст.")) else chapter
            }
        )
        assertNotEquals(
            TtsSynthesisPlanner.fingerprint(original),
            TtsSynthesisPlanner.fingerprint(modified),
        )
    }

    @Test fun fingerprintIgnoresEquivalentWhitespaceNormalization() {
        val a = sampleDocument()
        val b = a.copy(
            chapters = a.chapters.mapIndexed { index, chapter ->
                if (index == 0) chapter.copy(blocks = listOf("  Перше   речення.  ".repeat(30))) else chapter
            }
        )
        // The normalized readable content is intentionally different in repetition spacing only.
        assertEquals(TtsSynthesisPlanner.fingerprint(a), TtsSynthesisPlanner.fingerprint(b))
    }
}
