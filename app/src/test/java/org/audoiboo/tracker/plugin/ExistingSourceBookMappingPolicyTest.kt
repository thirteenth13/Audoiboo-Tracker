package org.audoiboo.tracker.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExistingSourceBookMappingPolicyTest {
    @Test
    fun preservesMappingWhenCanonicalBookStillBelongsToSeries() {
        val result = ExistingSourceBookMappingPolicy.resolve(
            mappedCanonicalBookId = "book-2",
            canonicalBookIds = setOf("book-1", "book-2", "book-3"),
            usedCanonicalBookIds = emptySet()
        )

        assertEquals("book-2", result)
    }

    @Test
    fun ignoresStaleMappingToBookOutsideCurrentSeries() {
        val result = ExistingSourceBookMappingPolicy.resolve(
            mappedCanonicalBookId = "old-book",
            canonicalBookIds = setOf("book-1", "book-2"),
            usedCanonicalBookIds = emptySet()
        )

        assertNull(result)
    }

    @Test
    fun doesNotReuseCanonicalBookAlreadyConsumedByAnotherObservation() {
        val result = ExistingSourceBookMappingPolicy.resolve(
            mappedCanonicalBookId = "book-2",
            canonicalBookIds = setOf("book-1", "book-2"),
            usedCanonicalBookIds = setOf("book-2")
        )

        assertNull(result)
    }
}
