package org.audoiboo.tracker

import org.audoiboo.tracker.plugin.BookSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePageOptionPolicyTest {
    private fun source(id: String, url: String, title: String? = null, confidence: Float = 1f) = BookSourceEntity(
        key = "$id:$url",
        canonicalBookId = "book-1",
        canonicalSeriesId = "series-1",
        sourceId = id,
        remoteKey = url,
        url = url,
        remoteTitle = title,
        confidence = confidence
    )

    @Test
    fun keepsDistinctUrlsFromSameProviderForPagePicker() {
        val first = source("baza-knig", "https://example.test/book/1", "Edition one", .9f)
        val second = source("baza-knig", "https://example.test/book/2", "Edition two", .8f)

        val options = SourcePageOptionPolicy.options(listOf(second, first))

        assertEquals(listOf(first.url, second.url), options.map { it.url })
        assertTrue(SourcePageOptionPolicy.needsObservationHint(first, options))
    }

    @Test
    fun duplicateNormalizedUrlAppearsOnlyOnce() {
        val first = source("izib", "https://example.test/book/1/", confidence = .9f)
        val duplicate = source("izib", "https://example.test/book/1", confidence = .8f)

        assertEquals(1, SourcePageOptionPolicy.options(listOf(duplicate, first)).size)
    }

    @Test
    fun downloadPickerStillUsesProviderLevelChoices() {
        val sources = listOf(
            source("baza-knig", "https://example.test/book/1"),
            source("baza-knig", "https://example.test/book/2"),
            source("izib", "https://other.test/book/1")
        )

        assertEquals(listOf("baza-knig", "izib"), SourcePageOptionPolicy.providerIds(sources))
        assertFalse(SourcePageOptionPolicy.needsObservationHint(sources.last(), sources))
    }

    @Test
    fun hintPrefersRemoteTitle() {
        val value = source("baza-knig", "https://example.test/book/1", "Alternative recording")
        assertEquals("Alternative recording", SourcePageOptionPolicy.observationHint(value))
    }
}
